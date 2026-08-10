package com.agent.coding.controller;

import com.agent.coding.toolcalls.ToolCallStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool-call lifecycle endpoints, ported from qwenpaw app/routers/tool_calls.py.
 * Backed by {@link ToolCallStore}; entries are registered by tool executors.
 */
@RestController
@RequestMapping("/api/tool-calls")
@CrossOrigin(origins = "*")
public class ToolCallsController {

    private final ToolCallStore store;

    public ToolCallsController(ToolCallStore store) {
        this.store = store;
    }

    private ToolCallStore.ToolCallEntry find(String sessionId, String toolCallId) {
        ToolCallStore.ToolCallEntry entry = store.get(toolCallId);
        if (entry == null || !sessionId.equals(entry.sessionId)) {
            return null;
        }
        return entry;
    }

    private ResponseEntity<Map<String, Object>> notFound(String toolCallId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Tool call not found: " + toolCallId));
    }

    @GetMapping("/{session_id}")
    public Map<String, Object> listCalls(@PathVariable String session_id) {
        List<ToolCallStore.ToolCallEntry> entries = store.list(session_id);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolCallStore.ToolCallEntry e : entries) {
            items.add(store.toInfo(e));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        return result;
    }

    @GetMapping("/{session_id}/{tool_call_id}")
    public ResponseEntity<?> getCall(@PathVariable String session_id,
                                     @PathVariable String tool_call_id) {
        ToolCallStore.ToolCallEntry entry = find(session_id, tool_call_id);
        if (entry == null) {
            return notFound(tool_call_id);
        }
        return ResponseEntity.ok(store.toInfo(entry));
    }

    @PostMapping("/{session_id}/{tool_call_id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String session_id,
                                    @PathVariable String tool_call_id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        ToolCallStore.ToolCallEntry entry = find(session_id, tool_call_id);
        if (entry == null) {
            return notFound(tool_call_id);
        }
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        if (!store.cancel(tool_call_id, force)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "Cannot cancel"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "tool_call_id", tool_call_id));
    }

    @PostMapping("/{session_id}/{tool_call_id}/extend-deadline")
    public ResponseEntity<?> extendDeadline(@PathVariable String session_id,
                                            @PathVariable String tool_call_id,
                                            @RequestBody Map<String, Object> body) {
        ToolCallStore.ToolCallEntry entry = find(session_id, tool_call_id);
        if (entry == null) {
            return notFound(tool_call_id);
        }
        Double seconds = body.get("seconds") == null ? null : ((Number) body.get("seconds")).doubleValue();
        boolean noDeadline = Boolean.TRUE.equals(body.get("no_deadline"));
        if (!store.extendDeadline(tool_call_id, seconds, noDeadline)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "Cannot extend deadline (capped or invalid)"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "tool_call_id", tool_call_id));
    }

    @PostMapping("/{session_id}/{tool_call_id}/offload")
    public ResponseEntity<?> offload(@PathVariable String session_id,
                                     @PathVariable String tool_call_id) {
        ToolCallStore.ToolCallEntry entry = find(session_id, tool_call_id);
        if (entry == null) {
            return notFound(tool_call_id);
        }
        if (!store.offload(tool_call_id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "Cannot offload (not running)"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "tool_call_id", tool_call_id));
    }

    @GetMapping("/{session_id}/{tool_call_id}/output")
    public ResponseEntity<?> getOutput(@PathVariable String session_id,
                                       @PathVariable String tool_call_id) {
        ToolCallStore.ToolCallEntry entry = find(session_id, tool_call_id);
        if (entry == null) {
            return notFound(tool_call_id);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool_call_id", tool_call_id);
        result.put("is_closed", !"running".equals(entry.status));
        result.put("final_state", entry.endState);
        result.put("content", entry.output == null || entry.output.isBlank()
                ? List.of() : List.of(Map.of("type", "text", "text", entry.output)));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{session_id}/{tool_call_id}/stream")
    public ResponseEntity<?> stream(@PathVariable String session_id,
                                    @PathVariable String tool_call_id) {
        ToolCallStore.ToolCallEntry entry = find(session_id, tool_call_id);
        if (entry == null) {
            return notFound(tool_call_id);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", entry.status);
        result.put("tool_call_id", tool_call_id);
        return ResponseEntity.ok(result);
    }
}
