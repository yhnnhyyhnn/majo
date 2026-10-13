package com.agent.coding.service;

import com.agent.coding.SettingsService;
import com.agent.coding.entity.ModelConfigEntity;
import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Speech-to-text via the provider's OpenAI-compatible transcription
 * endpoint ({@code POST {base_url}/audio/transcriptions}), activating the
 * previously stubbed /workspace/transcribe for the whisper_api provider
 * type. Pure JDK HttpClient — same no-SDK rule as the channel adapters.
 *
 * <p>Model resolution: the provider's first model row whose id mentions
 * whisper/transcribe/asr, else the OpenAI-conventional {@code whisper-1}.
 * Errors are readable messages, never silent empty text.
 */
@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_MODEL = "whisper-1";

    private final SettingsService settingsService;
    private final ProviderRepository providerRepo;
    private final ProviderModelRepository providerModelRepo;
    private final ModelConfigRepository modelConfigRepo;

    public TranscriptionService(SettingsService settingsService,
                                ProviderRepository providerRepo,
                                ProviderModelRepository providerModelRepo,
                                ModelConfigRepository modelConfigRepo) {
        this.settingsService = settingsService;
        this.providerRepo = providerRepo;
        this.providerModelRepo = providerModelRepo;
        this.modelConfigRepo = modelConfigRepo;
    }

    /** Resolved endpoint settings for the configured transcription provider. */
    private record Endpoint(String baseUrl, String apiKey, String model, String displayName) {}

    /** Thrown with a user-readable message; the controller maps it to 400/502. */
    public static class TranscriptionException extends RuntimeException {
        public TranscriptionException(String message) {
            super(message);
        }
    }

    /** Transcribe one audio recording; returns the text. */
    public String transcribe(byte[] audio, String filename, String contentType) {
        String type = settingsService.getTranscriptionProviderType();
        if (type == null || type.isBlank() || "disabled".equals(type)) {
            throw new TranscriptionException("语音转写未启用:请在 设置 → 语音转写 中选择 provider 类型");
        }
        if ("local_whisper".equals(type)) {
            throw new TranscriptionException("本地 Whisper 尚未支持:请改用 whisper_api(OpenAI 兼容 /audio/transcriptions)");
        }
        Endpoint endpoint = resolveEndpoint();
        return callTranscriptionEndpoint(endpoint, audio, filename, contentType);
    }

    private Endpoint resolveEndpoint() {
        String id = settingsService.getTranscriptionProviderId();
        if (id == null || id.isBlank()) {
            throw new TranscriptionException("未配置转写 provider:请先选择一个 OpenAI 兼容供应商");
        }
        // Built-in provider first, then custom (numeric model_configs id).
        var bp = providerRepo.findById(id).orElse(null);
        if (bp != null) {
            String base = bp.getBaseUrl();
            if (base == null || base.isBlank()) {
                throw new TranscriptionException("转写 provider '" + bp.getName() + "' 未配置 base_url");
            }
            String key = bp.getApiKey() == null ? "" : bp.getApiKey();
            if (Boolean.TRUE.equals(bp.getRequireApiKey()) && key.isBlank()) {
                throw new TranscriptionException("转写 provider '" + bp.getName() + "' 缺少 API key");
            }
            return new Endpoint(base, key, pickModel(bp.getId()), bp.getName());
        }
        try {
            long numeric = Long.parseLong(id);
            var mc = modelConfigRepo.findById(numeric).orElse(null);
            if (mc != null && mc.getBaseUrl() != null && !mc.getBaseUrl().isBlank()) {
                return new Endpoint(mc.getBaseUrl(), mc.getApiKey() == null ? "" : mc.getApiKey(),
                        DEFAULT_MODEL, mc.getName());
            }
        } catch (NumberFormatException ignored) {
        }
        throw new TranscriptionException("转写 provider '" + id + "' 不存在");
    }

    /** First whisper/transcribe/asr-ish model of the provider, else whisper-1. */
    private String pickModel(String providerId) {
        try {
            List<ProviderModelEntity> models = providerModelRepo.findByProviderId(providerId);
            for (var m : models) {
                String id = m.getModelId() == null ? "" : m.getModelId().toLowerCase();
                if (id.contains("whisper") || id.contains("transcri") || id.contains("asr")) {
                    return m.getModelId();
                }
            }
        } catch (Exception e) {
            log.debug("[transcribe] model lookup failed: {}", e.getMessage());
        }
        return DEFAULT_MODEL;
    }

    private String callTranscriptionEndpoint(Endpoint endpoint, byte[] audio,
                                             String filename, String contentType) {
        try {
            String boundary = "majo-" + Long.toHexString(System.nanoTime());
            byte[] body = buildMultipart(boundary, endpoint.model(), audio, filename, contentType);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(endpoint.baseUrl().replaceAll("/+$", "") + "/audio/transcriptions"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new TranscriptionException("转写请求失败 (HTTP " + response.statusCode() + "): "
                        + truncate(response.body()));
            }
            var root = MAPPER.readTree(response.body());
            String text = root.path("text").asText("");
            if (text.isBlank()) {
                throw new TranscriptionException("转写结果为空(供应商未返回 text 字段)");
            }
            return text;
        } catch (TranscriptionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[transcribe] request failed: {}", e.getMessage());
            throw new TranscriptionException("转写请求失败: " + e.getMessage());
        }
    }

    private static byte[] buildMultipart(String boundary, String model, byte[] audio,
                                         String filename, String contentType) {
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + (filename == null || filename.isBlank() ? "audio.webm" : filename) + "\"\r\n"
                + "Content-Type: " + (contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType) + "\r\n\r\n";
        String closing = "\r\n--" + boundary + "--\r\n";
        byte[] head = preamble.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = closing.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + audio.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(audio, 0, out, head.length, audio.length);
        System.arraycopy(tail, 0, out, head.length + audio.length, tail.length);
        return out;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
