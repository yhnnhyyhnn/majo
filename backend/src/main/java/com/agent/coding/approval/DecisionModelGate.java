package com.agent.coding.approval;

import com.agent.coding.service.ModelRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decision-model tool approval (ADR-0021, qwenpaw #8283 spirit port): a
 * two-pass, fail-closed verdict for guarded tool calls under the MODEL
 * approval level.
 *
 * <ol>
 *   <li><b>Deterministic hard-deny</b> — secret-shaped literals or
 *       sensitive paths in the tool input are refused without consulting
 *       any model (upstream's credential-scan idea, trimmed set);</li>
 *   <li><b>LLM binary judgment</b> — one non-streaming chat call to the
 *       agent's routed model with a compact verdict contract; ambiguity,
 *       timeouts and errors all resolve to deny.</li>
 * </ol>
 */
@Component
public class DecisionModelGate {

    private static final Logger log = LoggerFactory.getLogger(DecisionModelGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Verdict for one tool call. */
    public record Verdict(boolean approved, String reason) {
        static Verdict approve() {
            return new Verdict(true, "决策模型批准");
        }

        static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** Secret-shaped literals never belong in tool arguments. */
    private static final Pattern SENSITIVE_LITERAL = Pattern.compile(
            "(?i)\\b(?:sk-[a-z0-9_-]{16,}|gh[pousr]_[a-z0-9]{20,}|AKIA[0-9A-Z]{16})\\b");
    /** Paths whose contents are credentials by construction. */
    private static final Pattern SENSITIVE_PATH = Pattern.compile(
            "(?i)(\\.ssh|\\.aws|\\.gnupg|\\.kube|\\.env\\b|\\.netrc|\\.npmrc|\\.pypirc)");

    private final ModelRoutingService modelRouting;

    public DecisionModelGate(ModelRoutingService modelRouting) {
        this.modelRouting = modelRouting;
    }

    /** Overridable endpoint for tests; production resolves from the routing chain. */
    volatile String endpointOverride;

    /** Two-pass verdict; never throws. */
    public Verdict judge(String agentId, String toolName, Map<String, Object> input) {
        String serialized = serialize(input);
        if (SENSITIVE_LITERAL.matcher(serialized).find()) {
            return Verdict.deny("工具参数包含疑似密钥字面量,已按策略拒绝");
        }
        if (SENSITIVE_PATH.matcher(serialized).find()) {
            return Verdict.deny("工具参数涉及凭证目录(.ssh/.aws/.env 等),已按策略拒绝");
        }
        return askModel(agentId, toolName, serialized);
    }

    private Verdict askModel(String agentId, String toolName, String inputJson) {
        var slot = modelRouting.resolveEffectiveModel(agentId);
        if (slot == null || !slot.hasBoth()) {
            return Verdict.deny("无法解析决策模型(未配置生效模型),按策略拒绝");
        }
        var conn = modelRouting.resolveProviderConnection(slot.providerId());
        if (conn.baseUrl() == null || conn.baseUrl().isBlank()) {
            return Verdict.deny("无法解析决策模型连接,按策略拒绝");
        }
        String endpoint = endpointOverride != null ? endpointOverride
                : conn.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        String prompt = "你是一个工具调用风险审查器。请判断以下工具调用是否允许执行。\n"
                + "工具名: " + toolName + "\n"
                + "参数: " + inputJson + "\n"
                + "只回答一个词:允许执行回答 APPROVE;不应执行(危险/越权/破坏性/与请求无关)回答 DENY。";
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "model", slot.modelId(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 8,
                    "temperature", 0));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + conn.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[decision-model] HTTP {}: {}", resp.statusCode(), resp.body());
                return Verdict.deny("决策模型调用失败(HTTP " + resp.statusCode() + "),按策略拒绝");
            }
            String content = MAPPER.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("");
            return parseVerdict(content);
        } catch (Exception e) {
            log.warn("[decision-model] judgment failed: {}", e.getMessage());
            return Verdict.deny("决策模型判定不可得(" + e.getMessage() + "),按策略拒绝");
        }
    }

    /** APPROVE/DENY with tolerant fallbacks (Chinese synonyms, embedded words). */
    static Verdict parseVerdict(String content) {
        String normalized = content == null ? "" : content.trim().toUpperCase();
        if (normalized.contains("DENY") || normalized.contains("拒绝")) {
            return Verdict.deny("决策模型拒绝该工具调用");
        }
        if (normalized.contains("APPROVE") || normalized.contains("批准") || normalized.contains("允许")) {
            return Verdict.approve();
        }
        return Verdict.deny("决策模型回复无法解析,按策略拒绝");
    }

    private static String serialize(Map<String, Object> input) {
        try {
            return MAPPER.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }
}
