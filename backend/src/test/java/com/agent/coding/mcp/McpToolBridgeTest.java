package com.agent.coding.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests for MCP tool bridge assembly and policy resolution (ADR-0007). */
class McpToolBridgeTest {

    @Test
    void transportsMapToRegistrarSpellings() {
        assertEquals("stdio", McpService.mapTransportForRegistrar("stdio"));
        assertEquals("streamable-http", McpService.mapTransportForRegistrar("streamable_http"));
        assertEquals("streamable-http", McpService.mapTransportForRegistrar("streamablehttp"));
        assertEquals("sse", McpService.mapTransportForRegistrar("sse"));
        assertEquals("stdio", McpService.mapTransportForRegistrar(""));
        assertEquals("stdio", McpService.mapTransportForRegistrar("weird"));
    }

    @Test
    void toolOverrideWinsOverDefaults() {
        var policy = new McpModels.McpAccessPolicy(
                "allow",
                List.of(),
                List.of(new McpModels.McpToolDefaultPolicy("search", "ask")),
                List.of(new McpModels.McpToolAccessOverride(
                        "channel", "console", "all", "", "deny", "search")),
                0);
        assertEquals("deny", McpToolBridge.resolveEffect(policy, "search"));
    }

    @Test
    void toolDefaultAppliesWhenNoOverride() {
        var policy = new McpModels.McpAccessPolicy(
                "allow",
                List.of(),
                List.of(new McpModels.McpToolDefaultPolicy("search", "ask")),
                List.of(),
                0);
        assertEquals("ask", McpToolBridge.resolveEffect(policy, "search"));
        assertEquals("allow", McpToolBridge.resolveEffect(policy, "other_tool"));
    }

    @Test
    void defaultEffectAppliesWhenNothingMatches() {
        var policy = new McpModels.McpAccessPolicy("ask", List.of(), List.of(), List.of(), 0);
        assertEquals("ask", McpToolBridge.resolveEffect(policy, "anything"));
    }

    @Test
    void blankEffectFallsThroughToDefault() {
        var policy = new McpModels.McpAccessPolicy(
                "allow",
                List.of(),
                List.of(new McpModels.McpToolDefaultPolicy("search", " ")),
                List.of(),
                0);
        assertEquals("allow", McpToolBridge.resolveEffect(policy, "search"));
    }

    @Test
    void nullPolicyDenies() {
        assertEquals("deny", McpToolBridge.resolveEffect(null, "search"));
    }
}
