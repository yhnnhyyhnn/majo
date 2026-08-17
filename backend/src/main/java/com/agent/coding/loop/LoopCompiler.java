package com.agent.coding.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile declarative custom loop modes into a StopHandler, ported from
* Validates every gate's params against the
 * built-in catalog, enforces exclusive groups, and builds configured gates
 * in order.
 */
public class LoopCompiler {

    private final GateCatalog catalog;

    public LoopCompiler(GateCatalog catalog) {
        this.catalog = catalog;
    }

    public static LoopCompiler defaultCompiler() {
        return new LoopCompiler(GateCatalog.builtin());
    }

    /**
     * Compile one custom mode config (the JSON shape from running.loop.custom_modes)
     * into a StopHandler. Unknown or invalid gate types throw IllegalArgumentException.
     */
    public StopHandler compile(Map<String, Object> config) {
        StopHandler handler = new StopHandler();
        List<Map<String, Object>> gates = gatesOf(config);
        List<String> enabledTypes = new ArrayList<>();
        List<Map<String, Object>> enabled = new ArrayList<>();
        for (Map<String, Object> gate : gates) {
            if (Boolean.TRUE.equals(gate.get("enabled"))) {
                String type = String.valueOf(gate.get("type"));
                enabledTypes.add(type);
                enabled.add(gate);
            }
        }
        validateExclusiveGroups(enabledTypes);
        for (Map<String, Object> gate : enabled) {
            String type = String.valueOf(gate.get("type"));
            Map<String, Object> params = paramsOf(gate);
            StopGate stopGate = catalog.create(type, params);
            handler.register(stopGate);
        }
        return handler;
    }

    private void validateExclusiveGroups(List<String> enabledTypes) {
        Map<String, String> claimed = new LinkedHashMap<>();
        for (String type : enabledTypes) {
            if (!catalog.has(type)) {
                throw new IllegalArgumentException("Unknown built-in gate type: " + type);
            }
            String group = exclusiveGroupOf(type);
            if (group == null) {
                continue;
            }
            String owner = claimed.get(group);
            if (owner != null) {
                throw new IllegalArgumentException(
                        "Gates '" + owner + "' and '" + type + "' both claim exclusive group '" + group + "'");
            }
            claimed.put(group, type);
        }
    }

    private String exclusiveGroupOf(String type) {
        GateCatalog.Entry entry;
        try {
            entry = catalog.entry(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return entry.exclusiveGroup();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> gatesOf(Map<String, Object> config) {
        Object gates = config.get("gates");
        if (gates instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(Map<String, Object> gate) {
        Object params = gate.get("params");
        if (params instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}
