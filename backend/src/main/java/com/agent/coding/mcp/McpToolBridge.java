package com.agent.coding.mcp;

import com.agent.coding.inbox.InboxStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges the MCP configuration surface (client cards) into the shared
 * agent Toolkit via the harness {@link McpServerRegistrar} (ADR-0007).
 *
 * <p>Registers every enabled card at startup and re-registers a client
 * when its card changes (save/toggle/delete/whitelist update). Registration
 * results are logged and surfaced as inbox events. All work runs on a
 * single background thread so slow/failing MCP servers never block startup
 * or request handling.
 */
@Service
public class McpToolBridge {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final Toolkit toolkit;
    private final McpService mcpService;
    private final InboxStore inboxStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-tool-bridge");
        t.setDaemon(true);
        return t;
    });

    /** Client keys handed to the registrar (success or failure). */
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    public McpToolBridge(@org.springframework.context.annotation.Lazy Toolkit toolkit,
                         McpService mcpService,
                         InboxStore inboxStore) {
        this.toolkit = toolkit;
        this.mcpService = mcpService;
        this.inboxStore = inboxStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        executor.submit(this::registerAll);
    }

    @EventListener
    public void onCardChanged(McpCardChangedEvent event) {
        executor.submit(() -> reRegister(event.clientKey()));
    }

    /** Register every enabled MCP client card into the shared toolkit. */
    public synchronized void registerAll() {
        Map<String, McpServerConfig> configs = mcpService.buildServerConfigs();
        if (configs.isEmpty()) {
            log.info("[mcp-bridge] no enabled MCP clients to register");
            return;
        }
        log.info("[mcp-bridge] registering {} MCP client(s): {}", configs.size(), configs.keySet());
        attempted.addAll(configs.keySet());
        McpServerRegistrar.register(toolkit, configs, listener());
    }

    /** Re-register one client after a card change (remove, then re-add when enabled). */
    public synchronized void reRegister(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            return;
        }
        if (attempted.contains(clientKey)) {
            try {
                toolkit.removeMcpClient(clientKey).block(Duration.ofSeconds(10));
            } catch (Exception e) {
                log.debug("[mcp-bridge] removeMcpClient({}) returned: {}", clientKey, e.getMessage());
            }
            attempted.remove(clientKey);
        }
        McpServerConfig config = mcpService.buildServerConfig(clientKey);
        if (config == null) {
            log.info("[mcp-bridge] client '{}' disabled or removed; unregistered", clientKey);
            inboxStore.appendEvent(null, "mcp_bridge", clientKey, "mcp_unregistered", "ok",
                    "MCP server unregistered: " + clientKey,
                    "The MCP client was disabled or deleted; its tools are no longer available to agents.",
                    "info", null);
            return;
        }
        log.info("[mcp-bridge] re-registering MCP client '{}'", clientKey);
        attempted.add(clientKey);
        McpServerRegistrar.register(toolkit, Map.of(clientKey, config), listener());
    }

    /** Resolved access effect for one MCP tool call. */
    public record McpToolDecision(String clientKey, String effect) {}

    /**
     * Resolve the access effect for a tool call against the MCP access
     * policy of the client that owns it (ADR-0007 D4): per-tool override,
     * then per-tool default, then the client default effect. A missing or
     * disabled card resolves to {@code deny}.
     *
     * @return null when the tool is not an MCP tool (or lookups failed)
     */
    public McpToolDecision resolveToolDecision(String toolName) {
        try {
            io.agentscope.core.tool.AgentTool tool = toolkit.getTool(toolName);
            if (!(tool instanceof io.agentscope.core.tool.mcp.McpTool mcpTool)) {
                return null;
            }
            String clientKey = mcpTool.getClientName();
            Map<String, Object> card = McpStore.loadCardOrNull(clientKey);
            String deny = "deny";
            if (card == null || !Boolean.parseBoolean(String.valueOf(card.get("enabled")))) {
                return new McpToolDecision(clientKey, deny);
            }
            McpModels.McpAccessPolicy policy = mcpService.getPolicy(clientKey);
            String effect = resolveEffect(policy, toolName);
            return new McpToolDecision(clientKey, effect == null ? deny : effect.toLowerCase());
        } catch (Exception e) {
            log.debug("[mcp-bridge] policy lookup failed for '{}': {}", toolName, e.getMessage());
            return null;
        }
    }

    /**
     * Pure policy resolution: per-tool override, then per-tool default,
     * then the client default effect.
     */
    static String resolveEffect(McpModels.McpAccessPolicy policy, String toolName) {
        if (policy == null) {
            return "deny";
        }
        if (policy.toolOverrides() != null) {
            for (McpModels.McpToolAccessOverride o : policy.toolOverrides()) {
                if (toolName.equals(o.toolName()) && o.effect() != null && !o.effect().isBlank()) {
                    return o.effect();
                }
            }
        }
        if (policy.toolDefaults() != null) {
            for (McpModels.McpToolDefaultPolicy d : policy.toolDefaults()) {
                if (toolName.equals(d.toolName()) && d.effect() != null && !d.effect().isBlank()) {
                    return d.effect();
                }
            }
        }
        return policy.defaultEffect();
    }

    private McpServerRegistrationListener listener() {
        return result -> {
            String key = result.serverName();
            if (result.status() == McpServerRegistrationResult.Status.SUCCESS) {
                log.info("[mcp-bridge] MCP server '{}' registered ({})", key, result.transport());
                inboxStore.appendEvent(null, "mcp_bridge", key, "mcp_registered", "ok",
                        "MCP server registered: " + key,
                        "MCP tools from this server are now available to agents.",
                        "info", null);
            } else {
                Throwable cause = result.cause();
                log.warn("[mcp-bridge] MCP server '{}' registration {}: {}",
                        key, result.status(), cause == null ? "unknown reason" : cause.getMessage());
                inboxStore.appendEvent(null, "mcp_bridge", key, "mcp_failed", "error",
                        "MCP server registration failed: " + key,
                        cause == null ? result.status().toString() : String.valueOf(cause.getMessage()),
                        "error", null);
            }
        };
    }
}
