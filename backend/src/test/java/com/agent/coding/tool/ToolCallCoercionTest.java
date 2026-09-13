package com.agent.coding.tool;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for schema-guided tool-call input coercion (QwenPaw #6839 port). */
class ToolCallCoercionTest {

    private static Map<String, Object> schema(Map<String, Object> props) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        return s;
    }

    private static Map<String, Object> stringSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        return p;
    }

    @Test
    void coercesNumberToStringField() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", 123);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", stringSchema());

        ToolCallCoercion.Coerced r =
                ToolCallCoercion.coerceStringFields(input, schema(props));
        assertTrue(r.changed());
        assertEquals("123", r.input().get("path"));
    }

    @Test
    void coercesBooleanToStringField() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("flag", true);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("flag", stringSchema());

        ToolCallCoercion.Coerced r =
                ToolCallCoercion.coerceStringFields(input, schema(props));
        assertTrue(r.changed());
        assertEquals("true", r.input().get("flag"));
    }

    @Test
    void neverTouchesRealNumbers() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("count", 5);
        input.put("name", "x");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> intSchema = new LinkedHashMap<>();
        intSchema.put("type", "integer");
        props.put("count", intSchema);
        props.put("name", stringSchema());

        ToolCallCoercion.Coerced r =
                ToolCallCoercion.coerceStringFields(input, schema(props));
        assertFalse(r.changed());
        assertEquals(5, r.input().get("count"));
    }

    @Test
    void coercesNestedObjectProperties() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("assetInfo", 1.5);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("payload", nested);

        Map<String, Object> innerProps = new LinkedHashMap<>();
        innerProps.put("assetInfo", stringSchema());
        Map<String, Object> innerObj = new LinkedHashMap<>();
        innerObj.put("type", "object");
        innerObj.put("properties", innerProps);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("payload", innerObj);

        ToolCallCoercion.Coerced r =
                ToolCallCoercion.coerceStringFields(input, schema(props));
        assertTrue(r.changed());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) r.input().get("payload");
        assertEquals("1.5", out.get("assetInfo"));
    }

    @Test
    void coercesListItemsAgainstItemsSchema() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("paths", List.of("a", 2, 3.5));

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("items", stringSchema());
        Map<String, Object> arraySchema = new LinkedHashMap<>();
        arraySchema.put("type", "array");
        arraySchema.put("items", items.get("items"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("paths", arraySchema);

        ToolCallCoercion.Coerced r =
                ToolCallCoercion.coerceStringFields(input, schema(props));
        assertTrue(r.changed());
        assertEquals(List.of("a", "2", "3.5"), r.input().get("paths"));
    }

    @Test
    void understandsAnyOfStringBranches() {
        // Optional[str] arrives as anyOf: [string, null] — a number is not
        // accepted and string is permitted, so coercion applies.
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("opt", 42);
        Map<String, Object> anyOf = new LinkedHashMap<>();
        anyOf.put("anyOf", List.of(stringSchema(),
                Map.of("type", "null")));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("opt", anyOf);

        ToolCallCoercion.Coerced r =
                ToolCallCoercion.coerceStringFields(input, schema(props));
        assertTrue(r.changed());
        assertEquals("42", r.input().get("opt"));
    }

    @Test
    void returnsSameMapWhenNothingToCoerce() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "already-a-string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", stringSchema());

        ToolCallCoercion.Coerced r =
                ToolCallCoercion.coerceStringFields(input, schema(props));
        assertFalse(r.changed());
        assertEquals(input, r.input());
    }

    @Test
    void handlesNullSchemaSafely() {
        Map<String, Object> input = new HashMap<>();
        input.put("x", 1);
        ToolCallCoercion.Coerced r = ToolCallCoercion.coerceStringFields(input, null);
        assertFalse(r.changed());
        assertEquals(input, r.input());
    }
}
