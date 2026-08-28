package com.agent.coding.channel;

import com.agent.coding.ChatService;
import com.agent.coding.WorkspaceContext;
import com.agent.coding.accesscontrol.AccessControlStore;
import com.agent.coding.agent.AgentStore;
import com.agent.coding.security.ToolGuardHook;
import com.agent.coding.service.ModelRoutingService;
import com.agent.coding.skill.SkillService;
import com.agent.coding.skill.SkillStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Routes incoming channel messages through access control and the agent
 * loop, persists chat history, and delivers the reply back to the channel.
 */
@Service
public class ChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChannelDispatcher.class);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_REPLY_CHARS = 12_000;

    private static final String SYS_PROMPT = "你是一个个人 AI 助理，通过聊天渠道与用户交流。"
            + "回答简洁友好，中文优先。可用工具: read_file/write_file/edit_file/append_file(文件), "
            + "search_code/list_directory/execute_command(搜索与命令), "
            + "web_search/web_fetch(联网), get_current_time(时间), "
            + "spawn_subagent/chat_with_agent(子Agent协作)。";

    private final ModelRoutingService modelRouting;
    private final Toolkit toolkit;
    private final ToolGuardHook toolGuardHook;
    private final ChatService chatService;
    private final AccessControlStore accessControl;

    public ChannelDispatcher(ModelRoutingService modelRouting, Toolkit toolkit,
                             ToolGuardHook toolGuardHook, ChatService chatService,
                             AccessControlStore accessControl) {
        this.modelRouting = modelRouting;
        this.toolkit = toolkit;
        this.toolGuardHook = toolGuardHook;
        this.chatService = chatService;
        this.accessControl = accessControl;
    }

    /**
     * Handle an incoming message. Runs access control and, if allowed,
     * the agent loop; the reply is delivered through {@code reply}.
     */
    public void dispatch(Map<String, Object> config, ChannelMessage msg,
                         BiConsumer<String, String> reply) {
        String deny = accessCheck(config, msg);
        if (deny != null) {
            reply.accept(msg.replyTo(), deny);
            return;
        }
        if (msg.text() == null || msg.text().isBlank()) {
            return;
        }
        try {
            String replyText = runAgent(msg);
            reply.accept(msg.replyTo(), replyText);
        } catch (Exception e) {
            log.error("[channel:{}] agent run failed", msg.channelId(), e);
            reply.accept(msg.replyTo(), "抱歉，处理消息时出错了: " + e.getMessage());
        }
    }

    /** Access-control gate. Returns a denial message, or null to allow. */
    String accessCheck(Map<String, Object> config, ChannelMessage msg) {
        String channel = msg.channelId();
        String sender = msg.senderId();

        // 1) Global blacklist
        if (accessControl.isBlacklisted(channel, sender)) {
            log.info("[channel:{}] blacklisted sender {} ignored", channel, sender);
            return null; // silent ignore, no reply
        }
        // 2) Pending-approval flow when the channel has ACL data
        if (accessControl.hasData(channel) && !accessControl.isWhitelisted(channel, sender)) {
            accessControl.addPending(channel, sender, msg.text(), msg.senderName());
            log.info("[channel:{}] sender {} queued for approval", channel, sender);
            return "你的访问正在等待管理员审批，请稍后再试。";
        }
        // 3) Channel-level allowlist policy
        String policy = msg.groupId() == null
                ? SkillService.str(config.get("dm_policy"), "open")
                : SkillService.str(config.get("group_policy"), "open");
        if ("allowlist".equalsIgnoreCase(policy)) {
            List<?> allowFrom = config.get("allow_from") instanceof List<?> l ? l : List.of();
            boolean allowed = allowFrom.stream().anyMatch(a -> sender.equals(String.valueOf(a)));
            if (!allowed) {
                log.info("[channel:{}] sender {} not in allow_from, denied", channel, sender);
                return "你没有权限使用本服务。";
            }
        }
        return null;
    }

    private String runAgent(ChannelMessage msg) {
        String agentId = AgentStore.DEFAULT_AGENT_ID;
        String sessionId = "ch-" + msg.channelId() + "-" + Integer.toHexString(msg.identity().hashCode());
        String chatId = chatService.getOrCreateBySession(agentId, sessionId, msg.text()).getId();
        chatService.setStatus(chatId, "running");
        WorkspaceContext.set(workspaceFor(agentId).toString());

        HarnessAgent agent = buildAgent(agentId);
        if (agent == null) {
            chatService.setStatus(chatId, "idle");
            return "当前未配置可用的模型，无法回复。请在控制台设置中配置模型。";
        }
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(msg.senderId())
                .build();

        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        try {
            agent.streamEvents(new UserMessage(msg.text()), ctx)
                    .doOnNext(event -> {
                        String type = event.getClass().getSimpleName();
                        try {
                            if ("TextBlockDeltaEvent".equals(type)) {
                                text.append(event.getClass().getMethod("getDelta").invoke(event));
                            } else if ("ThinkingBlockDeltaEvent".equals(type)) {
                                thinking.append(event.getClass().getMethod("getDelta").invoke(event));
                            }
                        } catch (Exception ignored) {
                        }
                    })
                    .blockLast(RUN_TIMEOUT);
        } finally {
            WorkspaceContext.clear();
            saveChat(chatId, msg.text(), text.toString(), thinking.toString());
            chatService.setStatus(chatId, "idle");
        }

        String result = text.toString().trim();
        if (result.isBlank()) {
            result = "(没有生成回复)";
        }
        return result.length() > MAX_REPLY_CHARS
                ? result.substring(0, MAX_REPLY_CHARS) + "\n...[回复过长已截断]" : result;
    }

    private HarnessAgent buildAgent(String agentId) {
        var slot = modelRouting.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return null;
        }
        OpenAIChatModel model = modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        String name = "majo";
        var profile = AgentStore.getProfile(agentId);
        if (profile != null && profile.get("name") != null) {
            name = String.valueOf(profile.get("name"));
        }
        return HarnessAgent.builder()
                .name(name)
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(workspaceFor(agentId))
                .hook(toolGuardHook)
                .build();
    }

    private static Path workspaceFor(String agentId) {
        try {
            return AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            return SkillStore.WORKING_DIR;
        }
    }

    private void saveChat(String chatId, String userText, String replyText, String thinking) {
        List<Map<String, String>> msgs = new java.util.ArrayList<>();
        Map<String, String> u = new LinkedHashMap<>();
        u.put("role", "user");
        u.put("content", userText);
        msgs.add(u);
        Map<String, String> a = new LinkedHashMap<>();
        a.put("role", "assistant");
        a.put("content", replyText);
        if (thinking != null && !thinking.isBlank()) {
            a.put("thinking", thinking);
        }
        msgs.add(a);
        chatService.saveMessages(chatId, msgs);
    }
}
