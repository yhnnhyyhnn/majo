package com.agent.coding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frame-level tests for the ACP session pump (phase 1): a fake runner is
 * fed through a byte pipe while the pump consumes Content-Length framed
 * messages — verifying agent text accumulation, tool-call trace merging,
 * permission-request denial, and terminal-response handling — without
 * spawning a real process.
 */
class DelegateExternalAgentSessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode pump(DelegateExternalAgentTool tool, ByteArrayOutputStream out,
                                 PipedInputStream in, int respId, Object collector,
                                 long deadline) throws Exception {
        Method m = DelegateExternalAgentTool.class.getDeclaredMethod(
                "pumpUntilResponse", Writer.class, java.io.InputStream.class,
                Process.class, int.class, collector.getClass(), long.class);
        m.setAccessible(true);
        Writer writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
        return (JsonNode) m.invoke(tool, writer, in, null, respId, collector, deadline);
    }

    private static Object newCollector() throws Exception {
        Class<?> c = Class.forName("com.agent.coding.tool.DelegateExternalAgentTool$FrameCollector");
        var ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /** Content-Length framed JSON-RPC message as raw bytes. */
    private static byte[] frame(Map<String, Object> msg) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(msg);
            byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            var out = new ByteArrayOutputStream(header.length + body.length);
            out.write(header);
            out.write(body);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void pumpCollectsAgentChunksToolTraceAndDeniesPermission() throws Exception {
        PipedInputStream in = new PipedInputStream(65536);
        PipedOutputStream feed = new PipedOutputStream(in);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object collector = newCollector();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        Thread feeder = new Thread(() -> {
            try {
                feed.write(frame(Map.of("jsonrpc", "2.0", "method", "session/update",
                        "params", Map.of("sessionId", "s1", "update", Map.of(
                                "sessionUpdate", "agent_message_chunk",
                                "content", Map.of("type", "text", "text", "Hello "))))));
                feed.write(frame(Map.of("jsonrpc", "2.0", "method", "session/update",
                        "params", Map.of("sessionId", "s1", "update", Map.of(
                                "sessionUpdate", "agent_message_chunk",
                                "content", Map.of("type", "text", "text", "world"))))));
                feed.write(frame(Map.of("jsonrpc", "2.0", "method", "session/update",
                        "params", Map.of("sessionId", "s1", "update", Map.of(
                                "sessionUpdate", "tool_call", "toolCallId", "t1",
                                "title", "edit_file", "status", "in_progress")))));
                feed.write(frame(Map.of("jsonrpc", "2.0", "method", "session/update",
                        "params", Map.of("sessionId", "s1", "update", Map.of(
                                "sessionUpdate", "tool_call_update", "toolCallId", "t1",
                                "title", "edit_file", "status", "completed")))));
                feed.write(frame(Map.of("jsonrpc", "2.0", "id", 99, "method", "session/request_permission",
                        "params", Map.of("sessionId", "s1", "toolCall", Map.of("toolCallId", "t1")))));
                feed.write(frame(Map.of("jsonrpc", "2.0", "id", 3, "result",
                        Map.of("stopReason", "end_turn"))));
                feed.close();
            } catch (Exception e) {
                // pipe closed early — pump sees EOF
            }
        });
        feeder.start();

        JsonNode resp = pump(new DelegateExternalAgentTool(), out, in, 3, collector, deadline);
        feeder.join(2000);

        assertNotNull(resp, "prompt response must be returned");
        assertEquals("end_turn", resp.path("result").path("stopReason").asText());

        Field agentTextField = collector.getClass().getDeclaredField("agentText");
        agentTextField.setAccessible(true);
        String agentText = agentTextField.get(collector).toString().trim();
        assertEquals("Hello world", agentText);

        Method trace = collector.getClass().getDeclaredMethod("traceSummary");
        trace.setAccessible(true);
        String traceText = (String) trace.invoke(collector);
        assertTrue(traceText.contains("外部 Agent 工具调用"), traceText);
        assertTrue(traceText.contains("edit_file [completed]"), traceText);

        // The permission request must have been answered with a denied outcome.
        String written = out.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("cancelled"), written);
    }

    @Test
    void pumpReturnsNullOnImmediateEof() throws Exception {
        PipedInputStream in = new PipedInputStream();
        PipedOutputStream feed = new PipedOutputStream(in);
        feed.close(); // connected-then-closed → read() returns -1 (real EOF)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Object collector = newCollector();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        JsonNode resp = pump(new DelegateExternalAgentTool(), out, in, 3, collector, deadline);
        assertNull(resp, "EOF without response → null");
    }
}
