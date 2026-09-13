package com.agent.coding.mcp;

/** Published whenever an MCP client card is created/updated/toggled/deleted
 *  or its tool whitelist changes, so the {@link McpToolBridge} can
 *  re-register the server into the shared Toolkit (ADR-0007). */
public record McpCardChangedEvent(String clientKey) {}
