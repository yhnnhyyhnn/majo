package com.agent.coding.cron;

import com.agent.coding.ChatService;
import com.agent.coding.SettingsService;
import com.agent.coding.agent.AgentStore;
import com.agent.coding.inbox.InboxStore;
import com.agent.coding.service.ModelRoutingService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heartbeat scheduler (ADR-0013): periodically runs the default agent with
 * the workspace HEARTBEAT.md content as the user message — majo counterpart
 * of QwenPaw's cron heartbeat (a special {@code _heartbeat} cron job).
 *
 * <p>Config comes from the settings table (already surfaced via
 * {@code GET/PUT /config/heartbeat}): {@code every} is a 5-field cron
 * expression or an interval string ({@code 90s/30m/2h30m}, default
 * {@code 6h}); {@code target} main = run without delivery (inbox events on
 * failure only), inbox/last = result preview into the inbox (last has no
 * last-contact store in majo and falls back to inbox, ADR-0013).
 */
@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);
    private static final Pattern INTERVAL_PART = Pattern.compile("(\\d+)([smh])");
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(6);
    private static final String HEARTBEAT_FILE = "HEARTBEAT.md";
    // Dashes, not colons: the harness derives task-file paths from the
    // session id and ":" is illegal in Windows file names.
    private static final String SESSION_ID = "heartbeat-main";
    private static final String INBOX_SOURCE = "heartbeat";
    private static final int INBOX_PREVIEW_CHARS = 4000;

    private final SettingsService settingsService;
    private final ModelRoutingService modelRouting;
    private final ChatService chatService;
    private final Toolkit toolkit;
    private final InboxStore inboxStore;

    private final ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> scheduled;
    private final AtomicReference<LastRun> lastRun = new AtomicReference<>();

    private record LastRun(Instant startedAt, String status, String detail) {}

    public HeartbeatScheduler(SettingsService settingsService,
                              ModelRoutingService modelRouting,
                              ChatService chatService,
                              Toolkit toolkit,
                              InboxStore inboxStore) {
        this.settingsService = settingsService;
        this.modelRouting = modelRouting;
        this.chatService = chatService;
        this.toolkit = toolkit;
        this.inboxStore = inboxStore;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(1);
        this.scheduler.setThreadNamePrefix("heartbeat-");
        this.scheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onReady() {
        reschedule();
    }

    /** (Re)register the schedule from current settings; safe to call often. */
    public synchronized void reschedule() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        if (!settingsService.isHeartbeatEnabled()) {
            log.info("[heartbeat] disabled");
            return;
        }
        String every = settingsService.getHeartbeatEvery();
        try {
            if (isCronExpression(every)) {
                scheduled = scheduler.schedule(
                        this::runSafely, new CronTrigger("0 " + every.strip()));
            } else {
                Duration interval = parseInterval(every);
                scheduled = scheduler.scheduleAtFixedRate(this::runSafely, interval);
            }
            log.info("[heartbeat] scheduled every='{}'", every);
        } catch (Exception e) {
            log.warn("[heartbeat] failed to schedule '{}': {} — falling back to {}",
                    every, e.getMessage(), DEFAULT_INTERVAL);
            scheduled = scheduler.scheduleAtFixedRate(this::runSafely, DEFAULT_INTERVAL);
        }
    }

    /** Trigger one run immediately (async); returns whether it started. */
    public synchronized Map<String, Object> runNow() {
        boolean enabled = settingsService.isHeartbeatEnabled();
        if (enabled) {
            scheduler.execute(this::runSafely);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("started", enabled);
        result.put("last_run", lastRunSummary());
        return result;
    }

    public Map<String, Object> lastRunSummary() {
        LastRun lr = lastRun.get();
        Map<String, Object> m = new LinkedHashMap<>();
        if (lr == null) {
            m.put("status", "never");
            return m;
        }
        m.put("started_at", lr.startedAt().toString());
        m.put("status", lr.status());
        m.put("detail", lr.detail());
        return m;
    }

    // ── Run ──────────────────────────────────────────────────────────

    void runSafely() {
        Instant started = Instant.now();
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("[heartbeat] run failed: {}", e.getMessage());
            lastRun.set(new LastRun(started, "error", String.valueOf(e.getMessage())));
        }
    }

    private void runOnce() {
        String agentId = AgentStore.DEFAULT_AGENT_ID;
        Path workspace;
        try {
            workspace = AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            lastRun.set(new LastRun(Instant.now(), "skipped", "workspace unavailable"));
            return;
        }
        Path file = workspace.resolve(HEARTBEAT_FILE);
        String prompt;
        try {
            if (!Files.isRegularFile(file)) {
                lastRun.set(new LastRun(Instant.now(), "skipped", HEARTBEAT_FILE + " missing"));
                return;
            }
            prompt = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            lastRun.set(new LastRun(Instant.now(), "skipped", "read failed: " + e.getMessage()));
            return;
        }
        if (prompt.isEmpty()) {
            lastRun.set(new LastRun(Instant.now(), "skipped", HEARTBEAT_FILE + " empty"));
            return;
        }

        int timeout = Math.max(1, Math.min(settingsService.getHeartbeatTimeoutSeconds(), 3600));
        String target = settingsService.getHeartbeatTarget();
        String sessionId = SESSION_ID + "-" + agentId;
        try {
            var chat = chatService.getOrCreateBySession(agentId, sessionId, "Heartbeat");
            HarnessAgent agent = buildAgent(agentId, workspace);
            agent.streamEvents(new UserMessage(prompt),
                    RuntimeContext.builder().sessionId(sessionId).userId("heartbeat").build())
                .blockLast(Duration.ofSeconds(timeout));

            String reply = lastAssistantText(chat.getId());
            lastRun.set(new LastRun(Instant.now(), "success",
                    reply == null ? "(no text)" : preview(reply)));
            deliver(target, agentId, "success", "Heartbeat 完成",
                    reply == null ? "Heartbeat 运行完成（无文本输出）。" : reply);
        } catch (Exception e) {
            boolean timedOut = e instanceof java.util.concurrent.TimeoutException
                    || (e.getCause() instanceof java.util.concurrent.TimeoutException);
            String status = timedOut ? "timeout" : "error";
            String detail = timedOut ? "timed out after " + timeout + "s" : String.valueOf(e.getMessage());
            lastRun.set(new LastRun(Instant.now(), status, detail));
            deliver(target, agentId, status, timedOut ? "Heartbeat 超时" : "Heartbeat 失败", detail);
        }
    }

    /** main: no delivery on success (inbox on failure); inbox/last: always. */
    private void deliver(String target, String agentId, String status, String title, String body) {
        boolean deliverOnSuccess = !"main".equals(target);
        if (!"success".equals(status) || deliverOnSuccess) {
            try {
                inboxStore.appendEvent(agentId, INBOX_SOURCE, "", "heartbeat_result",
                        status, title, preview(body), "success".equals(status) ? "info" : "error",
                        Map.of("target", target, "status", status));
            } catch (Exception e) {
                log.warn("[heartbeat] inbox delivery failed: {}", e.getMessage());
            }
        }
    }

    private String lastAssistantText(String chatId) {
        try {
            var messages = chatService.getMessages(chatId);
            String text = null;
            for (var msg : messages) {
                if ("assistant".equals(msg.getRole()) && msg.getContent() != null
                        && !msg.getContent().isBlank()) {
                    text = msg.getContent();
                }
            }
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private HarnessAgent buildAgent(String agentId, Path workspace) {
        String name = "majo";
        var profile = AgentStore.getProfile(agentId);
        if (profile != null && profile.get("name") != null) {
            name = String.valueOf(profile.get("name"));
        }
        return HarnessAgent.builder()
                .name(agentId)
                .agentId(agentId)
                .sysPrompt(com.agent.coding.agent.ProtectedPrompt.withContract(
                        "你是通过心跳机制被周期性唤醒的个人助理。按照消息中的指令执行任务，"
                                + "简洁可靠地完成，不需要寒暄。"))
                .model(modelFor(agentId))
                .toolkit(toolkit)
                .workspace(workspace)
                .build();
    }

    private io.agentscope.extensions.model.openai.OpenAIChatModel modelFor(String agentId) {
        var slot = modelRouting.resolveEffectiveModel(agentId);
        if (slot != null && slot.hasBoth()) {
            return modelRouting.buildOpenAIChatModel(slot.providerId(), slot.modelId());
        }
        return io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
                .apiKey(settingsService.getApiKey())
                .baseUrl(settingsService.getBaseUrl())
                .modelName(settingsService.getModelName())
                .build();
    }

    // ── Schedule parsing (QwenPaw semantics) ────────────────────────

    /** True when the string is a 5-field cron expression. */
    static boolean isCronExpression(String every) {
        if (every == null) {
            return false;
        }
        String[] fields = every.strip().split("\\s+");
        if (fields.length != 5) {
            return false;
        }
        try {
            new CronTrigger("0 " + every.strip());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Parse {@code 90s} / {@code 30m} / {@code 2h30m} style intervals. */
    static Duration parseInterval(String every) {
        if (every == null || every.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        Matcher m = INTERVAL_PART.matcher(every.toLowerCase(Locale.ROOT));
        Duration total = Duration.ZERO;
        int matches = 0;
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() != lastEnd) {
                return DEFAULT_INTERVAL; // garbage between parts
            }
            long value = Long.parseLong(m.group(1));
            Duration part = switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(value);
                case "m" -> Duration.ofMinutes(value);
                default -> Duration.ofHours(value);
            };
            total = total.plus(part);
            matches++;
            lastEnd = m.end();
        }
        if (matches == 0 || lastEnd != every.strip().length()) {
            return DEFAULT_INTERVAL;
        }
        return total.isZero() || total.isNegative() ? DEFAULT_INTERVAL : total;
    }

    private static String preview(String text) {
        String stripped = text.strip();
        return stripped.length() > INBOX_PREVIEW_CHARS
                ? stripped.substring(0, INBOX_PREVIEW_CHARS) + "…(truncated)" : stripped;
    }
}
