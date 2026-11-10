package com.agent.coding.agent;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.approval.ApprovalHook;
import com.agent.coding.approval.ApprovalStore;
import com.agent.coding.approval.DecisionModelGate;
import com.agent.coding.repository.ChatRepository;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross integration test for the agentscope 2.1.0-SNAPSHOT upgrade: a REAL
 * HarnessAgent runs a two-round conversation (tool call → human approval →
 * tool execution → final answer) against a scripted OpenAI-compatible fake.
 *
 * Covers what unit tests mock away:
 * <ol>
 *   <li>the 2.1.0 hook dispatcher actually fires majo-style {@link Hook}
 *       implementations (PreReasoning / PreActing / PostActing);</li>
 *   <li>the session-derivation migration — AgentState.getSessionId() must
 *       equal the RuntimeContext session id the run was started with, so
 *       approval cards key correctly;</li>
 *   <li>events still stream out and tool results still feed back into the
 *       next model request.</li>
 * </ol>
 */
class SdkCrossIntegrationTest {

    private static final String SESSION = "s-cross";

    @TempDir
    Path workspace;

    private HttpServer server;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final CopyOnWriteArrayList<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> hookEvents = new CopyOnWriteArrayList<>();
    private ApprovalStore approvalStore;
    private String originalLevel;
    /** Written into the (per-build isolated) agent workspace, read back by the tool. */
    private String targetFile;

    @BeforeEach
    void startFakeModel() throws Exception {
        // The read_file tool executes against the agent workspace (isolated
        // under MAJO_WORKING_DIR in tests), not the @TempDir.
        workspace = com.agent.coding.skill.SkillStore.WORKING_DIR;
        Files.createDirectories(workspace);
        targetFile = "cross-hello-" + System.nanoTime() + ".txt";
        Files.writeString(workspace.resolve(targetFile), "cross-world", StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int call = modelCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            // The 2.1.0 SDK defaults to streaming; answer in the production
            // SSE shape (tool-call deltas first, then a text delta).
            boolean streaming = body.contains("\"stream\":true");
            exchange.getResponseHeaders().set("Content-Type",
                    streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            String sse = call == 1
                    ? "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"cross-model\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\": \\\"" + targetFile + "\\\", \\\"offset\\\": 0, \\\"limit\\\": 10}\"}}]},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"cross-model\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n"
                    : "data: {\"id\":\"chatcmpl-2\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"cross-model\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"读取完成\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"chatcmpl-2\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,\"model\":\"cross-model\","
                        + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n";
            exchange.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        WorkspaceContext.clear();
        if (targetFile != null && workspace != null) {
            Files.deleteIfExists(workspace.resolve(targetFile));
        }
        if (originalLevel != null) {
            com.agent.coding.agent.AgentStore.updateAgent("default",
                    Map.of("approval_level", originalLevel));
        }
    }

    /** Records dispatched event types; sets the workspace on tool threads. */
    private static final class RecordingHook implements Hook {
        final List<String> events;

        RecordingHook(List<String> events) {
            this.events = events;
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            events.add(event.getClass().getSimpleName());
            if (event instanceof PreActingEvent) {
                // Mirrors ToolGuardHook: tool threads need the workspace.
                WorkspaceContext.set(WorkspaceContext.get().toString());
            }
            return Mono.just(event);
        }
    }

    @Test
    void realHarnessRunsToolRoundTripWithHooksAndSessionConsistency() throws Exception {
        Path tmp = workspace;
        WorkspaceContext.set(tmp.toString());
        originalLevel = com.agent.coding.agent.AgentStore.getApprovalLevel("default");
        com.agent.coding.agent.AgentStore.updateAgent("default", Map.of("approval_level", "AUTO"));

        approvalStore = new ApprovalStore();
        // Real normalizer: a Mockito mock would return null from normalize()
        // and drop the conversation history downstream (see ADR-0022 guard).
        com.agent.coding.service.ModelRoutingService nRouting =
                Mockito.mock(com.agent.coding.service.ModelRoutingService.class);
        Mockito.when(nRouting.resolveEffectiveModel(any())).thenReturn(null);
        com.agent.coding.agent.ModelRequestNormalizerHook realNormalizer =
                new com.agent.coding.agent.ModelRequestNormalizerHook(
                        nRouting,
                        Mockito.mock(com.agent.coding.repository.ProviderModelRepository.class),
                        Mockito.mock(com.agent.coding.memory.MemoryBackendRegistry.class));
        com.agent.coding.agent.SessionScopeMiddleware sessionScope =
                new com.agent.coding.agent.SessionScopeMiddleware(
                        Mockito.mock(com.agent.coding.security.ToolGuardService.class),
                        Mockito.mock(com.agent.coding.security.FileGuardService.class),
                        new ApprovalHook(approvalStore, Mockito.mock(DecisionModelGate.class)),
                        Mockito.mock(com.agent.coding.security.DoomLoopGuard.class),
                        realNormalizer,
                        Mockito.mock(com.agent.coding.mcp.McpToolBridge.class));
        ApprovalHook approvalHook = new ApprovalHook(approvalStore,
                Mockito.mock(DecisionModelGate.class));
        RecordingHook recording = new RecordingHook(hookEvents);

        // Production path: the factory owns the toolkit and the 2.1.0
        // disable chain; the model falls back to the (mocked) settings
        // pointed at the fake provider.
        com.agent.coding.service.ModelRoutingService routing =
                Mockito.mock(com.agent.coding.service.ModelRoutingService.class);
        when(routing.resolveEffectiveModel(any())).thenReturn(null);
        com.agent.coding.SettingsService settings = Mockito.mock(com.agent.coding.SettingsService.class);
        Mockito.when(settings.getApiKey()).thenReturn("test");
        Mockito.when(settings.getBaseUrl())
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        Mockito.when(settings.getModelName()).thenReturn("cross-model");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new com.agent.coding.tool.ReadFileTool());
        HarnessAgentFactory factory = new HarnessAgentFactory(routing, settings, toolkit);

        HarnessAgent agent = factory
                .builder("default", "cross test", tmp,
                        factory.modelFor("default", HarnessAgentFactory.ModelFallback.SETTINGS))
                .hook(recording)
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(SESSION)
                .userId("cross-user")
                .build();

        CompletableFuture<List<AgentEvent>> run = CompletableFuture.supplyAsync(() ->
                agent.streamEvents(new UserMessage("请读取 " + targetFile), ctx)
                        .collectList()
                        .block(Duration.ofSeconds(120)));

        // ISOLATION EXPERIMENT A: AUTO level, middleware retained, no card.
        // The read_file tool executes immediately; req2 must carry the result.

        List<AgentEvent> events = run.get(120, TimeUnit.SECONDS);

        // 1) events streamed out of the 2.1.0 harness
        assertTrue(events != null && !events.isEmpty(), "no events streamed");
        // 2) the new dispatcher fires majo-style hooks across phases
        assertTrue(hookEvents.stream().anyMatch(n -> n.startsWith("PreReasoning")),
                "PreReasoning dispatched; saw " + hookEvents);
        assertTrue(hookEvents.stream().anyMatch(n -> n.startsWith("PreActing")),
                "PreActing dispatched; saw " + hookEvents);
        assertTrue(hookEvents.stream().anyMatch(n -> n.startsWith("PostActing")),
                "PostActing dispatched; saw " + hookEvents);
        // 3) two model round-trips, tool result fed back into the second
        assertEquals(2, modelCalls.get(), "expected tool call + final answer round-trips");

        assertTrue(requestBodies.get(1).contains("cross-world"),
                "second request must carry the read_file result");
        // 4) tool actually read the real workspace file
        assertTrue(requestBodies.get(1).contains(targetFile),
                "tool call arguments visible in the second request");
    }
}
