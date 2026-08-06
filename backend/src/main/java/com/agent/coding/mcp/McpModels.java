package com.agent.coding.mcp;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Console request/response models.
 *
 * <p>Field names are serialised with {@code snake_case} to match the
 * frontend contract (frontend/src/api/types/mcp.ts), which mirrors the
 * pydantic payloads of the qwenpaw reference implementation.</p>
 */
public final class McpModels {

    private McpModels() {}

    // ---- constants (mirror qwenpaw/drivers/constants.py) ----
    public static final String PROTOCOL_MCP = "mcp";
    public static final String CAPABILITY_KIND_TOOL = "tool";
    public static final String CREDENTIAL_ALIAS_STATIC = "static";
    public static final String CREDENTIAL_ALIAS_OAUTH = "oauth";
    public static final String CREDENTIAL_KIND_STATIC = "static";
    public static final String CREDENTIAL_KIND_OAUTH_AUTH_CODE = "oauth2_auth_code";
    public static final String POLICY_EFFECT_ALLOW = "allow";
    public static final String POLICY_EFFECT_ASK = "ask";
    public static final String POLICY_EFFECT_DENY = "deny";
    public static final String POLICY_TARGET_WILDCARD = "*";
    public static final String PRINCIPAL_SOURCE_CHANNEL = "channel";
    public static final String PRINCIPAL_SUBJECT_ALL = "all";
    public static final String PRINCIPAL_SUBJECT_USER = "user";
    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable_http";
    public static final String TRANSPORT_SSE = "sse";

    public static final List<String> MCP_EFFECTS =
            List.of(POLICY_EFFECT_ALLOW, POLICY_EFFECT_ASK, POLICY_EFFECT_DENY);
    public static final List<String> MCP_TRANSPORTS =
            List.of(TRANSPORT_STDIO, TRANSPORT_STREAMABLE_HTTP, TRANSPORT_SSE);

    // ---- MCPClientOAuthStatus ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpClientOAuthStatus(
            boolean authorized,
            double expiresAt,
            String scope,
            String clientId) {}

    // ---- MCPAccessSummary ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpAccessSummary(
            String defaultEffect,
            int overridesCount) {}

    // ---- MCPClientInfo ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpClientInfo(
            String key,
            String name,
            String description,
            boolean enabled,
            String transport,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env,
            String cwd,
            List<String> tools,
            McpClientOAuthStatus oauthStatus,
            McpAccessSummary accessSummary) {}

    // ---- MCPClientData (create/update client body) ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpClientData(
            String name,
            String description,
            Boolean enabled,
            String transport,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env,
            String cwd,
            List<String> tools) {

        public McpClientData {
            if (description == null) description = "";
            if (enabled == null) enabled = true;
            if (transport == null) transport = TRANSPORT_STDIO;
            if (url == null) url = "";
            if (headers == null) headers = Map.of();
            if (command == null) command = "";
            if (args == null) args = List.of();
            if (env == null) env = Map.of();
            if (cwd == null) cwd = "";
        }
    }

    // ---- MCPClientCreateRequest: {client_key, client} ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpClientCreateRequest(String clientKey, McpClientData client) {}

    // ---- MCPClientUpdateRequest (all fields optional) ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpClientUpdateRequest(
            String name,
            String description,
            Boolean enabled,
            String transport,
            String url,
            Map<String, String> headers,
            String command,
            List<String> args,
            Map<String, String> env,
            String cwd,
            List<String> tools) {}

    // ---- MCPToolInfo ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpToolInfo(
            String name,
            String description,
            boolean enabled,
            Map<String, Object> inputSchema) {}

    // ---- MCPToolWhitelistRequest: {tools} ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpToolWhitelistRequest(List<String> tools) {}

    // ---- MCPAccessRule ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpAccessRule(
            String sourceType,
            String sourceValue,
            String subjectType,
            String subjectValue,
            String effect) {

        public McpAccessRule {
            if (sourceType == null) sourceType = PRINCIPAL_SOURCE_CHANNEL;
            if (sourceValue == null) sourceValue = "console";
            if (subjectType == null) subjectType = PRINCIPAL_SUBJECT_ALL;
            if (subjectValue == null) subjectValue = "";
        }
    }

    // ---- MCPAccessPrincipalOption ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpAccessPrincipalOption(
            String sourceType,
            String sourceValue,
            String subjectType,
            String subjectValue,
            String label,
            String chatId,
            String chatName,
            String sessionId,
            String updatedAt) {}

    // ---- MCPToolDefaultPolicy ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpToolDefaultPolicy(String toolName, String effect) {}

    // ---- MCPToolAccessOverride: MCPAccessRule + tool_name ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpToolAccessOverride(
            String sourceType,
            String sourceValue,
            String subjectType,
            String subjectValue,
            String effect,
            String toolName) {}

    // ---- MCPAccessPolicy ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpAccessPolicy(
            String defaultEffect,
            List<McpAccessRule> clientOverrides,
            List<McpToolDefaultPolicy> toolDefaults,
            List<McpToolAccessOverride> toolOverrides,
            int unmanagedRulesCount) {

        public McpAccessPolicy {
            if (defaultEffect == null) defaultEffect = POLICY_EFFECT_DENY;
            if (clientOverrides == null) clientOverrides = new ArrayList<>();
            if (toolDefaults == null) toolDefaults = new ArrayList<>();
            if (toolOverrides == null) toolOverrides = new ArrayList<>();
        }
    }

    // ---- MCPOAuthStartRequest ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpOAuthStartRequest(
            String url,
            String scope,
            String clientId,
            String authEndpoint,
            String tokenEndpoint) {}

    // ---- MCPOAuthStartResponse: {auth_url, session_id} ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpOAuthStartResponse(String authUrl, String sessionId) {}

    // ---- MCPOAuthStatusResponse: {authorized, expires_at, scope} ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpOAuthStatusResponse(boolean authorized, double expiresAt, String scope) {}

    // ---- Generic {message} response ----
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record McpMessageResponse(String message) {}
}
