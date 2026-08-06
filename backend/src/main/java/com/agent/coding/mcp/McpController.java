package com.agent.coding.mcp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Global MCP console endpoints ({@code /api/mcp}).
 *
 * <p>Port of qwenpaw/app/routers/mcp.py. All routes are agent-scope-agnostic;
 * the frontend (frontend/src/api/modules/mcp.ts) talks to these directly from
 * the MCP management page.</p>
 */
@RestController
@RequestMapping("/api/mcp")
@CrossOrigin(origins = "*")
public class McpController {

    private static final int DEFAULT_PRINCIPALS_LIMIT = 100;

    private final McpService mcpService;
    private final McpOAuthService oauthService;

    public McpController(McpService mcpService, McpOAuthService oauthService) {
        this.mcpService = mcpService;
        this.oauthService = oauthService;
    }

    // ===== Clients =====

    @GetMapping
    public List<McpModels.McpClientInfo> listClients() {
        return mcpService.listClients();
    }

    @GetMapping("/{clientKey}")
    public McpModels.McpClientInfo getClient(@PathVariable String clientKey) {
        Map<String, Object> card = mcpService.loadCardOrNull(clientKey);
        if (card == null) {
            throw new McpException(404, "MCP client '" + clientKey + "' not found");
        }
        return mcpService.buildInfoFromCard(card);
    }

    @PostMapping
    public McpModels.McpClientInfo createClient(
            @RequestBody McpModels.McpClientCreateRequest request) {
        if (request == null || request.clientKey() == null || request.clientKey().isBlank()) {
            throw new McpException(400, "Field 'client_key' is required");
        }
        if (request.client() == null) {
            throw new McpException(400, "Field 'client' is required");
        }
        return mcpService.createClient(request.clientKey().strip(), request.client());
    }

    @PutMapping("/{clientKey}")
    public McpModels.McpClientInfo updateClient(
            @PathVariable String clientKey,
            @RequestBody McpModels.McpClientUpdateRequest updates) {
        return mcpService.updateClient(clientKey, updates);
    }

    @PatchMapping("/toggle/{clientKey}")
    public McpModels.McpClientInfo toggleClient(@PathVariable String clientKey) {
        return mcpService.toggleClient(clientKey);
    }

    @DeleteMapping("/{clientKey}")
    public McpModels.McpMessageResponse deleteClient(@PathVariable String clientKey) {
        return mcpService.deleteClient(clientKey);
    }

    // ===== Tools =====

    @GetMapping("/tools/{clientKey}")
    public List<McpModels.McpToolInfo> listTools(@PathVariable String clientKey) {
        return mcpService.listTools(clientKey);
    }

    @PutMapping("/tools/{clientKey}")
    public List<McpModels.McpToolInfo> updateToolWhitelist(
            @PathVariable String clientKey,
            @RequestBody(required = false) McpModels.McpToolWhitelistRequest request) {
        List<String> tools = request == null ? null : request.tools();
        return mcpService.updateToolWhitelist(clientKey, tools);
    }

    // ===== Access principals =====

    @GetMapping("/access-principals")
    public List<McpModels.McpAccessPrincipalOption> listAccessPrincipals() {
        return mcpService.listAccessPrincipals(DEFAULT_PRINCIPALS_LIMIT);
    }

    // ===== Policy =====

    @GetMapping("/policy/{clientKey}")
    public McpModels.McpAccessPolicy getPolicy(@PathVariable String clientKey) {
        return mcpService.getPolicy(clientKey);
    }

    @PutMapping("/policy/{clientKey}")
    public McpModels.McpAccessPolicy updatePolicy(
            @PathVariable String clientKey,
            @RequestBody McpModels.McpAccessPolicy access) {
        return mcpService.updatePolicy(clientKey, access);
    }

    // ===== OAuth =====

    @PostMapping("/oauth/start/{clientKey}")
    public McpModels.McpOAuthStartResponse startOAuth(
            @PathVariable String clientKey,
            @RequestBody(required = false) McpModels.McpOAuthStartRequest body,
            HttpServletRequest request) {
        String redirectUri = McpOAuthService.buildRedirectUri(
                request.getScheme(), request.getServerName(), request.getServerPort());
        return oauthService.startOAuth(clientKey, body, redirectUri);
    }

    @GetMapping("/oauth/status/{clientKey}")
    public McpModels.McpOAuthStatusResponse oauthStatus(@PathVariable String clientKey) {
        return oauthService.oauthStatus(clientKey);
    }

    @DeleteMapping("/oauth/{clientKey}")
    public McpModels.McpMessageResponse revokeOAuth(@PathVariable String clientKey) {
        return oauthService.oauthRevoke(clientKey);
    }

    @GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public String oauthCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription) {
        return oauthService.oauthCallback(code, state, error, errorDescription);
    }
}
