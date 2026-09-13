package com.agent.coding.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema-guided type coercion for tool-call inputs. Ported from QwenPaw
 * {@code agents/utils/tool_call_coerce.py} (issue #6839): models sometimes
 * emit unquoted numbers or booleans for parameters declared as
 * {@code type: string}; strict validation on the receiving side (notably
 * MCP servers) rejects such values outright, so every such tool call fails.
 *
 * <p>The walk follows the value and the schema in lockstep (map ↔
 * {@code properties}, list ↔ {@code items}) so nested structures common in
 * MCP schemas are covered, as are union shapes such as
 * {@code type: ["string", "null"]} or {@code anyOf}. Fields declared with
 * any other type are left untouched — a value whose type already satisfies
 * its schema node is never coerced — so real numeric arguments are not
 * corrupted and already-correct inputs come back unchanged.
 */
public final class ToolCallCoercion {

    /** Result of a coercion pass: the (possibly rebuilt) input map. */
    public record Coerced(Map<String, Object> input, boolean changed) {}

    private ToolCallCoercion() {}

    /**
     * Coerce values of schema-declared {@code string} fields to strings.
     *
     * @return the original map when nothing changed, otherwise a rebuilt map
     */
    public static Coerced coerceStringFields(Map<String, Object> parsed,
                                             Map<String, Object> schema) {
        if (parsed == null || schema == null || schema.isEmpty()) {
            return new Coerced(parsed, false);
        }
        Map<String, Object> out = coerceMap(parsed, schema);
        boolean changed = !out.equals(parsed);
        return new Coerced(changed ? out : parsed, changed);
    }

    // ── Lockstep walk ────────────────────────────────────────────────

    private static Map<String, Object> coerceMap(Map<String, Object> value,
                                                 Map<String, Object> schema) {
        Map<String, Object> props = schemaProperties(schema);
        Map<String, Object> out = new LinkedHashMap<>(value.size());
        for (Map.Entry<String, Object> e : value.entrySet()) {
            Object propSchema = props.get(e.getKey());
            @SuppressWarnings("unchecked")
            Map<String, Object> propSchemaMap = propSchema instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            out.put(e.getKey(), coerceNode(e.getValue(), propSchemaMap));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> coerceList(List<?> value, Map<String, Object> schema) {
        Object items = schema == null ? null : schema.get("items");
        Map<String, Object> itemSchema = items instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        List<Object> out = new ArrayList<>(value.size());
        for (Object item : value) {
            out.add(coerceNode(item, itemSchema));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object coerceNode(Object value, Map<String, Object> schema) {
        if (value instanceof Map<?, ?> m) {
            return coerceMap((Map<String, Object>) m, schema == null ? Map.of() : schema);
        }
        if (value instanceof List<?> l) {
            return coerceList(l, schema);
        }
        // Leaves. Booleans first — they are not numbers in JSON Schema.
        if (value instanceof Boolean b) {
            if (!schemaAccepts(b, schema) && schemaPermitsString(schema)) {
                return b ? "true" : "false";
            }
            return b;
        }
        if (value instanceof Number n) {
            if (!schemaAccepts(n, schema) && schemaPermitsString(schema)) {
                return numberToString(n);
            }
            return n;
        }
        // Genuine strings and null are never touched.
        return value;
    }

    /** JSON literal string form of a number (Jackson has no source text). */
    private static String numberToString(Number n) {
        if (n instanceof Double d && d.isNaN()) {
            return "NaN";
        }
        if (n instanceof Double d && d.isInfinite()) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        return String.valueOf(n);
    }

    // ── Schema inspection (lightweight jsonschema approximation) ─────

    /** JSON Schema type names {@code value} satisfies (jsonschema semantics). */
    private static Set<String> jsonTypesOf(Object value) {
        Set<String> types = new LinkedHashSet<>();
        if (value instanceof Boolean) {
            types.add("boolean");
        } else if (value instanceof Integer || value instanceof Long) {
            types.add("integer");
            types.add("number");
        } else if (value instanceof Number n) {
            types.add("number");
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && !Double.isNaN(d)) {
                types.add("integer");
            }
        } else if (value instanceof String) {
            types.add("string");
        } else if (value == null) {
            types.add("null");
        } else if (value instanceof Map) {
            types.add("object");
        } else if (value instanceof List) {
            types.add("array");
        }
        return types;
    }

    /**
     * Whether {@code schema} accepts {@code value} as-is, for coercion
     * gating. Covers the shapes tool schemas actually use ({@code type} as
     * string or list, {@code anyOf}/{@code oneOf}/{@code allOf}); value
     * constraints are ignored — erring towards "accepted" skips coercion,
     * which is always safe.
     */
    private static boolean schemaAccepts(Object value, Map<String, Object> schema) {
        if (schema == null) {
            return true;
        }
        Object declared = schema.get("type");
        if (declared != null) {
            Set<String> types = jsonTypesOf(value);
            if (declared instanceof String s) {
                return types.contains(s);
            }
            if (declared instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s && types.contains(s)) {
                        return true;
                    }
                }
                return false;
            }
        }
        for (String combinator : List.of("anyOf", "oneOf")) {
            Object branches = schema.get(combinator);
            if (branches instanceof List<?> list && !list.isEmpty()) {
                boolean any = false;
                for (Object b : list) {
                    if (b instanceof Map<?, ?> m && schemaAccepts(value, (Map<String, Object>) m)) {
                        any = true;
                        break;
                    }
                }
                return any;
            }
        }
        Object allOf = schema.get("allOf");
        if (allOf instanceof List<?> list && !list.isEmpty()) {
            for (Object b : list) {
                if (b instanceof Map<?, ?> m && !schemaAccepts(value, (Map<String, Object>) m)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether {@code schema} can validate a string value at all. */
    private static boolean schemaPermitsString(Map<String, Object> schema) {
        if (schema == null) {
            return false;
        }
        Object declared = schema.get("type");
        if ("string".equals(declared)) {
            return true;
        }
        if (declared instanceof List<?> list) {
            for (Object item : list) {
                if ("string".equals(item)) {
                    return true;
                }
            }
        }
        for (String combinator : List.of("anyOf", "oneOf")) {
            Object branches = schema.get(combinator);
            if (branches instanceof List<?> list) {
                for (Object b : list) {
                    if (b instanceof Map<?, ?> m && schemaPermitsString((Map<String, Object>) m)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Map<String, Object> schemaProperties(Map<String, Object> schema) {
        if (schema == null) {
            return Map.of();
        }
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return Map.of();
    }
}
