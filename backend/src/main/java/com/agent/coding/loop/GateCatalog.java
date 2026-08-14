package com.agent.coding.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit whitelist of built-in user-configurable gates, ported from
 * qwenpaw loop/catalog.py GateCatalog. Describes each gate with stable
 * frontend metadata and a JSON-schema-like params declaration.
 */
public class GateCatalog {

    /** One catalog entry: metadata + a factory producing a configured gate. */
    public record Entry(String type, String title, String description, String category,
                        Map<String, Object> paramsSchema, String cost, String exclusiveGroup,
                        GateFactory factory) {
    }

    public interface GateFactory {
        StopGate create(Map<String, Object> params);
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public GateCatalog(List<Entry> entryList) {
        for (Entry e : entryList) {
            entries.put(e.type(), e);
        }
    }

    /** List entries in registration order (frontend metadata + schema). */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Entry e : entries.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type());
            m.put("title", e.title());
            m.put("description", e.description());
            m.put("category", e.category());
            m.put("schema", schema(e));
            m.put("cost", e.cost());
            m.put("exclusive_group", e.exclusiveGroup());
            result.add(m);
        }
        return result;
    }

    public Entry entry(String gateType) {
        Entry e = entries.get(gateType);
        if (e == null) {
            throw new IllegalArgumentException("Unknown built-in gate type: " + gateType);
        }
        return e;
    }

    public boolean has(String gateType) {
        return entries.containsKey(gateType);
    }

    /** Validate params for one catalog type and build a configured gate. */
    public StopGate create(String gateType, Map<String, Object> params) {
        Entry e = entry(gateType);
        return e.factory().create(params == null ? Map.of() : params);
    }

    private static Map<String, Object> schema(Entry e) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", e.paramsSchema());
        return schema;
    }

    /** The process-wide built-in catalog (7 gates, matching qwenpaw). */
    public static GateCatalog builtin() {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("iteration", "Iteration limit",
                "Stop after a fixed number of loop iterations.", "limits",
                Map.of("max_iterations", Map.of("type", "integer", "default", 40, "minimum", 1, "maximum", 500)),
                "none", null,
                params -> new IterationGate(intParam(params, "max_iterations", 40))));
        list.add(new Entry("doom_loop", "Repetition protection",
                "Detect repeated tool calls and change strategy.", "safety",
                Map.of("window_size", Map.of("type", "integer", "default", 3, "minimum", 2, "maximum", 20),
                        "similarity_threshold", Map.of("type", "number", "default", 1.0, "minimum", 0.0, "maximum", 1.0)),
                "none", null,
                params -> DoomLoopGate.defaultConfig()));
        list.add(new Entry("token_budget", "Token budget",
                "Limit prompt and completion token usage.", "limits",
                Map.of("max_total_tokens", Map.of("type", "integer", "default", 120000, "minimum", 1)),
                "none", null,
                params -> new TokenBudgetGate(intParam(params, "max_total_tokens", 120000))));
        list.add(new Entry("timeout", "Loop time limit",
                "Stop at the next loop boundary after elapsed time.", "limits",
                Map.of("max_seconds", Map.of("type", "number", "default", 1800.0, "minimum", 1.0, "maximum", 86400.0)),
                "none", null,
                params -> new TimeoutGate(doubleParam(params, "max_seconds", 1800.0))));
        list.add(new Entry("tool_call_budget", "Tool-call budget",
                "Limit all calls and selected tools.", "limits",
                Map.of("max_calls", Map.of("type", "integer", "default", 30, "minimum", 1, "maximum", 10000)),
                "none", null,
                params -> new IterationGate(intParam(params, "max_calls", 30))));
        list.add(new Entry("qualitative_rubric", "Qualitative completion check",
                "Check text responses without tool calls using natural-language criteria.", "quality",
                Map.of("rubric", Map.of("type", "string", "default",
                        "Verify the task before stopping. Continue if work remains.")),
                "none", "completion_rubric",
                params -> new CompletionGate(null, 3)));
        list.add(new Entry("completion_rubric", "Completion signal check",
                "Check text responses without tool calls for a completion signal.", "quality",
                Map.of("completion_signal", Map.of("type", "string", "default", "COMPLETED")),
                "model_call", "completion_rubric",
                params -> new CompletionGate(List.of(strParam(params, "completion_signal", "COMPLETED")), 3)));
        return new GateCatalog(list);
    }

    private static int intParam(Map<String, Object> params, String key, int def) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return def;
    }

    private static double doubleParam(Map<String, Object> params, String key, double def) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return def;
    }

    private static String strParam(Map<String, Object> params, String key, String def) {
        Object v = params.get(key);
        return v == null ? def : String.valueOf(v);
    }
}
