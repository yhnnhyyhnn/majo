package com.agent.coding.approval;

import com.agent.coding.service.ModelRoutingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Decision-model gate (ADR-0021): deterministic hard-deny runs before any
 * model call, the LLM verdict is parsed tolerantly, and every failure mode
 * — HTTP error, timeout, unparsable reply, unresolvable model — denies
 * (fail-closed).
 */
class DecisionModelGateTest {

    private HttpServer server;
    private final AtomicReference<String> lastPrompt = new AtomicReference<>("(none)");
    private volatile int status = 200;
    private volatile String content = "APPROVE";

    private DecisionModelGate gateWithModel() {
        ModelRoutingService routing = mock(ModelRoutingService.class);
        when(routing.resolveEffectiveModel(any())).thenReturn(
                new ModelRoutingService.ModelSlot("prov", "judge-model"));
        when(routing.resolveProviderConnection("prov")).thenReturn(
                new ModelRoutingService.ProviderConnection(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-j", "OpenAIChatModel"));
        return new DecisionModelGate(routing);
    }

    @BeforeEach
    void startFakeModel() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastPrompt.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeModel() {
        server.stop(0);
    }

    @Test
    void approvesOnExplicitApprove() {
        content = "APPROVE";
        DecisionModelGate.Verdict v = gateWithModel().judge(
                "a1", "execute_command", Map.of("command", "ls -la"));
        assertTrue(v.approved());
        assertTrue(lastPrompt.get().contains("execute_command"));
    }

    @Test
    void deniesOnDenyAndChineseSynonyms() {
        content = "DENY";
        assertFalse(gateWithModel().judge("a1", "execute_command",
                Map.of("command", "rm -rf /")).approved());
        content = "拒绝";
        assertFalse(gateWithModel().judge("a1", "execute_command",
                Map.of("command", "rm -rf /")).approved());
    }

    @Test
    void unparsableReplyDenies() {
        content = "我觉得可以吧";
        assertFalse(gateWithModel().judge("a1", "write_file",
                Map.of("path", "a.txt")).approved());
    }

    @Test
    void httpErrorDenies() {
        content = "APPROVE";
        status = 500;
        assertFalse(gateWithModel().judge("a1", "execute_command",
                Map.of("command", "ls")).approved());
    }

    @Test
    void unresolvableModelDenies() {
        ModelRoutingService routing = mock(ModelRoutingService.class);
        when(routing.resolveEffectiveModel(any())).thenReturn(
                ModelRoutingService.ModelSlot.empty());
        DecisionModelGate gate = new DecisionModelGate(routing);

        DecisionModelGate.Verdict v = gate.judge("a1", "execute_command", Map.of("command", "ls"));
        assertFalse(v.approved());
        assertTrue(v.reason().contains("决策模型"));
    }

    @Test
    void secretLiteralHardDeniesWithoutModelCall() {
        DecisionModelGate.Verdict v = gateWithModel().judge(
                "a1", "write_file",
                Map.of("path", "cfg.txt", "content", "key = sk-abcdefghijklmnopq12345"));
        assertFalse(v.approved());
        assertTrue(v.reason().contains("密钥"));
        org.junit.jupiter.api.Assertions.assertEquals("(none)", lastPrompt.get(), "hard-deny must not reach the model");
    }

    @Test
    void sensitivePathHardDeniesWithoutModelCall() {
        DecisionModelGate.Verdict v = gateWithModel().judge(
                "a1", "read_file", Map.of("path", "/home/u/.ssh/id_rsa"));
        assertFalse(v.approved());
        org.junit.jupiter.api.Assertions.assertEquals("(none)", lastPrompt.get());
    }
}
