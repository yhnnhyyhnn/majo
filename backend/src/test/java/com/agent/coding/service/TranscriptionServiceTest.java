package com.agent.coding.service;

import com.agent.coding.SettingsService;
import com.agent.coding.entity.ProviderEntity;
import com.agent.coding.entity.ProviderModelEntity;
import com.agent.coding.repository.ModelConfigRepository;
import com.agent.coding.repository.ProviderModelRepository;
import com.agent.coding.repository.ProviderRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * whisper_api transcription (#8083 companion): the previously stubbed
 * /workspace/transcribe now relays to the configured provider's
 * OpenAI-compatible /audio/transcriptions endpoint. A local HttpServer
 * stands in for the provider so request shape (multipart, model, auth) and
 * response handling are verified without network.
 */
class TranscriptionServiceTest {

    private HttpServer server;
    private final SettingsService settings = new SettingsService(org.mockito.Mockito.mock(com.agent.coding.repository.SettingsRepository.class));
    private final ProviderRepository providerRepo = mock(ProviderRepository.class);
    private final ProviderModelRepository providerModelRepo = mock(ProviderModelRepository.class);
    private final TranscriptionService service = new TranscriptionService(
            settings, providerRepo, providerModelRepo, mock(ModelConfigRepository.class));

    /** Captured request from the fake provider endpoint. */
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int respondWith = 200;
    private volatile String responseBody = "{\"text\":\"你好世界\"}";

    @BeforeEach
    void startFakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(respondWith, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeProvider() {
        server.stop(0);
    }

    private void configure(String baseUrl) {
        settings.setTranscriptionProviderType("whisper_api");
        settings.setTranscriptionProviderId("transcribe-p");
        ProviderEntity p = new ProviderEntity();
        p.setId("transcribe-p");
        p.setName("Transcribe Provider");
        p.setBaseUrl(baseUrl);
        p.setApiKey("sk-transcribe");
        when(providerRepo.findById("transcribe-p")).thenReturn(java.util.Optional.of(p));
        ProviderModelEntity asr = new ProviderModelEntity();
        asr.setProviderId("transcribe-p");
        asr.setModelId("whisper-large-3");
        when(providerModelRepo.findByProviderId(anyString()))
                .thenReturn(List.of(asr));
    }

    @Test
    void relaysMultipartToProviderAndParsesText() {
        configure("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        String text = service.transcribe(new byte[]{1, 2, 3, 4}, "clip.webm", "audio/webm");

        assertEquals("你好世界", text);
        assertEquals("Bearer sk-transcribe", lastAuth.get());
        String body = lastBody.get();
        assertTrue(body.contains("name=\"model\""), "model field present");
        assertTrue(body.contains("whisper-large-3"), "resolved asr model used");
        assertTrue(body.contains("filename=\"clip.webm\""), "original filename kept");
        assertTrue(body.contains("audio/webm"), "content type kept");
        assertTrue(body.contains("--majo-"), "multipart boundary present");
    }

    @Test
    void providerErrorsSurfaceAsReadableMessages() {
        configure("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        respondWith = 401;
        responseBody = "{\"error\":{\"message\":\"bad key\"}}";

        TranscriptionService.TranscriptionException e = assertThrows(
                TranscriptionService.TranscriptionException.class,
                () -> service.transcribe(new byte[]{1}, "a.wav", "audio/wav"));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
    }

    @Test
    void blankTextIsAnErrorNotSilence() {
        configure("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        responseBody = "{\"text\":\"\"}";

        assertThrows(TranscriptionService.TranscriptionException.class,
                () -> service.transcribe(new byte[]{1}, "a.wav", "audio/wav"));
    }

    @Test
    void disabledTypeIsRejected() {
        settings.setTranscriptionProviderType("disabled");

        TranscriptionService.TranscriptionException e = assertThrows(
                TranscriptionService.TranscriptionException.class,
                () -> service.transcribe(new byte[]{1}, "a.wav", "audio/wav"));
        assertTrue(e.getMessage().contains("未启用"), e.getMessage());
    }

    @Test
    void localWhisperReportsUnsupported() {
        settings.setTranscriptionProviderType("local_whisper");

        TranscriptionService.TranscriptionException e = assertThrows(
                TranscriptionService.TranscriptionException.class,
                () -> service.transcribe(new byte[]{1}, "a.wav", "audio/wav"));
        assertTrue(e.getMessage().contains("本地 Whisper"), e.getMessage());
    }

    @Test
    void missingProviderIsRejected() {
        settings.setTranscriptionProviderType("whisper_api");
        settings.setTranscriptionProviderId("ghost");
        when(providerRepo.findById("ghost")).thenReturn(java.util.Optional.empty());

        TranscriptionService.TranscriptionException e = assertThrows(
                TranscriptionService.TranscriptionException.class,
                () -> service.transcribe(new byte[]{1}, "a.wav", "audio/wav"));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    @Test
    void fallsBackToWhisper1WithoutAsrModel() {
        configure("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        when(providerModelRepo.findByProviderId(anyString())).thenReturn(List.of());

        service.transcribe(new byte[]{1}, "a.wav", "audio/wav");

        assertTrue(lastBody.get().contains("whisper-1"), lastBody.get());
    }
}
