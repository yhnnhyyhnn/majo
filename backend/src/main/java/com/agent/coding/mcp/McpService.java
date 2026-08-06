package com.agent.coding.mcp;

import com.agent.coding.entity.ChatEntity;
import com.agent.coding.repository.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.agent.coding.mcp.McpModels.CAPABILITY_KIND_TOOL;
import static com.agent.coding.mcp.McpModels.CREDENTIAL_ALIAS_STATIC;
import static com.agent.coding.mcp.McpModels.CREDENTIAL_KIND_STATIC;
import static com.agent.coding.mcp.McpModels.POLICY_EFFECT_ASK;
import static com.agent.coding.mcp.McpModels.POLICY_TARGET_WILDCARD;
import static com.agent.coding.mcp.McpModels.PRINCIPAL_SOURCE_CHANNEL;
import static com.agent.coding.mcp.McpModels.PRINCIPAL_SUBJECT_USER;
import static com.agent.coding.mcp.McpModels.TRANSPORT_STDIO;
import static com.agent.coding.mcp.McpModels.TRANSPORT_STREAMABLE_HTTP;
import static com.agent.coding.mcp.McpModels.TRANSPORT_SSE;

/**
 * Application service for Console-managed MCP client configuration.
 *
 * <p>Port of qwenpaw/app/mcp/config_service.py + the card/credential
 * builders from drivers/adapters/mcp_card_builder.py. Cards are plain JSON
 * maps persisted via {@link McpStore}.</p>
 */
@Service
public class McpService {

    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private static final List<String> RESERVED_KEY_PREFIXES = List.of(
            "access-principals/", "tools/", "toggle/", "oauth/", "policy/");

    private static final DateTimeFormatter ISO_LOCAL =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ChatRepository chatRepository;
    private final McpProtocolClient protocolClient = new McpProtocolClient();

    public McpService(ChatRepository chatRepository) {
        this.chatRepository = chatRepository;
    }

    // ------------------------------------------------------------------
    // Card loading
    // ------------------------------------------------------------------

    public Map<String, Object> loadCard(String clientKey) {
        return McpStore.loadCard(clientKey);
    }

    public Map<String, Object> loadCardOrNull(String clientKey) {
        return McpStore.loadCardOrNull(clientKey);
    }

    public List<Map<String, Object>> listCards() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String key : McpStore.listClientKeys()) {
            Map<String, Object> card = McpStore.loadCardOrNull(key);
            if (card != null) cards.add(card);
        }
        return cards;
    }

    // ------------------------------------------------------------------
    // Info building
    // ------------------------------------------------------------------

    /** Build the MCPClientInfo payload from a stored card. */
    public McpModels.McpClientInfo buildInfoFromCard(Map<String, Object> card) {
        Map<String, Object> credentials = asMap(card.get("credentials"));
        Map<String, Object> staticRef = credentialRefByAliasOrKind(
                credentials, CREDENTIAL_ALIAS_STATIC, CREDENTIAL_KIND_STATIC);
        Map<String, Object> credential = null;
        if (staticRef != null) {
            String ref = str(staticRef.get("ref"));
            if (!ref.isEmpty()) credential = McpStore.loadCredentialOrNull(ref);
        }
        Map<String, Object> oauthRef = credentialRefByAliasOrKind(
                credentials, McpModels.CREDENTIAL_ALIAS_OAUTH,
                McpModels.CREDENTIAL_KIND_OAUTH_AUTH_CODE);
        String oauthCredRef = oauthRef != null && !str(oauthRef.get("ref")).isEmpty()
                ? str(oauthRef.get("ref"))
                : McpStore.mcpOauthCredentialRef(str(card.get("name")));
        Map<String, Object> oauthCredential = McpStore.loadCredentialOrNull(oauthCredRef);

        return buildInfoPayload(card, credential, oauthCredential);
    }

    /** Port of card_builder.build_mcp_client_info_payload. */
    private McpModels.McpClientInfo buildInfoPayload(
            Map<String, Object> card,
            Map<String, Object> credential,
            Map<String, Object> oauthCredential) {
        Map<String, Object> endpoint = asMap(card.get("endpoint"));
        Map<String, Object> config = asMap(card.get("config"));
        String transport = str(endpoint.get("transport"));
        if (transport.isEmpty()) transport = TRANSPORT_STDIO;

        Map<String, String> staticSecrets = secretsOf(credential);
        Map<String, String> env = McpBinding.bindingToResponse(
                asMap(endpoint.get("env")), staticSecrets, CREDENTIAL_ALIAS_STATIC);
        Map<String, String> headers = McpBinding.bindingToResponse(
                asMap(endpoint.get("headers")), staticSecrets, CREDENTIAL_ALIAS_STATIC);

        String name = str(config.get("display_name"));
        if (name.isEmpty()) name = str(card.get("name"));

        return new McpModels.McpClientInfo(
                str(card.get("name")),
                name,
                str(config.get("description")),
                asBool(card.get("enabled"), true),
                transport,
                str(endpoint.get("url")),
                headers,
                str(endpoint.get("command")),
                stringList(endpoint.get("args")),
                env,
                str(endpoint.get("cwd")),
                config.containsKey("tools") ? stringListOrNull(config.get("tools")) : null,
                oauthStatusOf(oauthCredential),
                new McpModels.McpAccessSummary(
                        McpPolicy.defaultEffectOf(asMap(card.get("policy"))),
                        McpPolicy.countToolAccessOverrides(asMap(card.get("policy")))));
    }

    /** Port of card_builder._oauth_status. */
    private McpModels.McpClientOAuthStatus oauthStatusOf(Map<String, Object> record) {
        if (record == null) return null;
        Map<String, Object> secrets = asMap(record.get("secrets"));
        Map<String, Object> publicMap = asMap(record.get("public"));
        String accessToken = str(secrets.get("access_token"));
        double expiresAt = toDouble(publicMap.get("expires_at"), 0.0);
        boolean authorized = !accessToken.isEmpty()
                && (expiresAt <= 0 || expiresAt > (System.currentTimeMillis() / 1000.0));
        return new McpModels.McpClientOAuthStatus(
                authorized,
                expiresAt,
                str(publicMap.get("scope")),
                str(publicMap.get("client_id")));
    }

    // ------------------------------------------------------------------
    // List clients / tools
    // ------------------------------------------------------------------

    public List<McpModels.McpClientInfo> listClients() {
        List<McpModels.McpClientInfo> result = new ArrayList<>();
        for (Map<String, Object> card : listCards()) {
            result.add(buildInfoFromCard(card));
        }
        return result;
    }

    /** Query a connected MCP server for its available tools. */
    public List<McpModels.McpToolInfo> listTools(String clientKey) {
        Map<String, Object> card = loadCard(clientKey);
        if (!asBool(card.get("enabled"), true)) return List.of();
        List<Map<String, Object>> capabilities;
        try {
            capabilities = queryToolCapabilities(card);
        } catch (Exception exc) {
            log.warn("Failed to list tools for MCP client '{}': {}", clientKey, exc.getMessage());
            throw new McpException(502,
                    "Failed to query tools from MCP server: " + exc.getMessage());
        }

        Map<String, Object> config = asMap(card.get("config"));
        List<String> whitelist = stringListOrNull(config.get("tools"));
        Set<String> whitelistSet = whitelist == null ? null : Set.copyOf(whitelist);

        List<McpModels.McpToolInfo> tools = new ArrayList<>();
        for (Map<String, Object> capability : capabilities) {
            String capName = str(capability.get("name"));
            tools.add(new McpModels.McpToolInfo(
                    capName,
                    str(capability.get("description")),
                    whitelistSet == null || whitelistSet.contains(capName),
                    asMap(capability.get("input_schema"))));
        }
        return tools;
    }

    /** Update tool whitelist; return full tool list (empty on query failure). */
    public List<McpModels.McpToolInfo> updateToolWhitelist(
            String clientKey, List<String> tools) {
        Map<String, Object> card = loadCard(clientKey);
        Map<String, Object> config = asMap(card.get("config"));
        config.put("tools", tools);
        card.put("config", config);
        McpStore.saveCard(clientKey, card);
        try {
            return listTools(clientKey);
        } catch (McpException e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> queryToolCapabilities(Map<String, Object> card) throws Exception {
        Map<String, Object> endpoint = asMap(card.get("endpoint"));
        String transport = str(endpoint.get("transport"));
        if (transport.isEmpty()) transport = TRANSPORT_STDIO;

        Map<String, String> staticSecrets = secretsOf(McpStore.loadCredentialOrNull(
                McpStore.mcpCredentialRef(str(card.get("name")))));
        Map<String, String> oauthSecrets = secretsOf(McpStore.loadCredentialOrNull(
                McpStore.mcpOauthCredentialRef(str(card.get("name")))));

        String url = str(endpoint.get("url"));
        Map<String, String> headers = resolveBindingMap(
                asMap(endpoint.get("headers")), staticSecrets, oauthSecrets);
        Map<String, String> env = McpBinding.resolveEnvBinding(
                asMap(endpoint.get("env")), staticSecrets, oauthSecrets);

        McpProtocolClient.ClientEndpoint resolved = new McpProtocolClient.ClientEndpoint(
                transport, url, headers,
                str(endpoint.get("command")),
                stringList(endpoint.get("args")),
                env,
                str(endpoint.get("cwd")));
        return protocolClient.listTools(resolved);
    }

    private Map<String, String> resolveBindingMap(
            Map<String, Object> binding,
            Map<String, String> staticSecrets,
            Map<String, String> oauthSecrets) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : binding.entrySet()) {
            String value = McpBinding.resolveBindingSpec(e.getValue(), staticSecrets, oauthSecrets);
            result.put(e.getKey(), value);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Policy
    // ------------------------------------------------------------------

    public McpModels.McpAccessPolicy getPolicy(String clientKey) {
        Map<String, Object> card = loadCard(clientKey);
        return McpPolicy.accessPolicyFromCard(asMap(card.get("policy")));
    }

    public McpModels.McpAccessPolicy updatePolicy(
            String clientKey, McpModels.McpAccessPolicy access) {
        Map<String, Object> card = loadCard(clientKey);
        Map<String, Object> policy = McpPolicy.policyFromAccessUpdate(
                asMap(card.get("policy")), access);
        card.put("policy", policy);
        McpStore.saveCard(clientKey, card);
        return McpPolicy.accessPolicyFromCard(policy);
    }

    // ------------------------------------------------------------------
    // Access principals
    // ------------------------------------------------------------------

    /** Return recent source-scoped users for Console policy editing. */
    public List<McpModels.McpAccessPrincipalOption> listAccessPrincipals(int limit) {
        List<ChatEntity> chats;
        try {
            chats = chatRepository.findAllByArchivedAtIsNullOrderByUpdatedAtDesc();
        } catch (Exception e) {
            log.warn("Failed to list chat identities for MCP access policy", e);
            return List.of();
        }

        Map<String, McpModels.McpAccessPrincipalOption> byIdentity = new LinkedHashMap<>();
        for (ChatEntity chat : chats) {
            String sourceValue = chat.getChannel() == null ? "" : chat.getChannel().strip();
            String subjectValue = chat.getUserId() == null ? "" : chat.getUserId().strip();
            if (sourceValue.isEmpty() || subjectValue.isEmpty()) continue;
            String key = PRINCIPAL_SOURCE_CHANNEL + "\u0000" + sourceValue + "\u0000" + subjectValue;
            String chatName = chat.getTitle() == null ? "" : chat.getTitle();
            String updatedAt = chat.getUpdatedAt() == null ? null : ISO_LOCAL.format(chat.getUpdatedAt());
            McpModels.McpAccessPrincipalOption option = new McpModels.McpAccessPrincipalOption(
                    PRINCIPAL_SOURCE_CHANNEL,
                    sourceValue,
                    PRINCIPAL_SUBJECT_USER,
                    subjectValue,
                    principalLabel(sourceValue, subjectValue, chatName),
                    chat.getId() == null ? "" : chat.getId(),
                    chatName,
                    chat.getSessionId() == null ? "" : chat.getSessionId(),
                    updatedAt);
            McpModels.McpAccessPrincipalOption existing = byIdentity.get(key);
            if (existing == null || updatedAtOf(option) >= updatedAtOf(existing)) {
                byIdentity.put(key, option);
            }
        }

        List<McpModels.McpAccessPrincipalOption> result =
                new ArrayList<>(byIdentity.values());
        result.sort((a, b) -> Double.compare(updatedAtOf(b), updatedAtOf(a)));
        if (result.size() > limit) result = result.subList(0, limit);
        return result;
    }

    private static String principalLabel(String sourceValue, String subjectValue, String chatName) {
        String base = sourceValue + " / " + subjectValue;
        return chatName == null || chatName.isEmpty() ? base : base + " (" + chatName + ")";
    }

    private static double updatedAtOf(McpModels.McpAccessPrincipalOption option) {
        if (option.updatedAt() == null) return 0.0;
        try {
            return java.time.LocalDateTime.parse(option.updatedAt(), ISO_LOCAL)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    public McpModels.McpClientInfo createClient(
            String clientKey, McpModels.McpClientData client) {
        validateClientKey(clientKey);
        if (McpStore.cardExists(clientKey)) {
            throw new McpException(400,
                    "MCP client '" + clientKey + "' already exists. Use PUT to update.");
        }
        String displayName = normalizeMcpDisplayName(client.name(), clientKey);
        ensureMcpDisplayNameUnique(displayName, clientKey);

        Map<String, Object> credential = buildMcpCredentialRecord(
                clientKey, client, null);
        Map<String, Object> card = buildMcpDriverCard(
                clientKey, client, McpStore.mcpCredentialRef(clientKey),
                credential, null);

        if (!asMap(credential.get("secrets")).isEmpty()) {
            McpStore.saveCredential(McpStore.mcpCredentialRef(clientKey), credential);
        } else {
            McpStore.deleteCredential(McpStore.mcpCredentialRef(clientKey));
        }
        McpStore.saveCard(clientKey, card);
        return buildInfoFromCard(card);
    }

    public McpModels.McpClientInfo updateClient(
            String clientKey, McpModels.McpClientUpdateRequest updates) {
        Map<String, Object> existingCard = loadCard(clientKey);
        McpModels.McpClientInfo existingInfo = buildInfoFromCard(existingCard);
        McpModels.McpClientData merged = mergeUpdateWithExisting(existingInfo, updates);
        String displayName = normalizeMcpDisplayName(merged.name(), clientKey);
        ensureMcpDisplayNameUnique(displayName, clientKey);

        Map<String, Object> existingCredential = McpStore.loadCredentialOrNull(
                McpStore.mcpCredentialRef(clientKey));
        Map<String, Object> credential = buildMcpCredentialRecord(
                clientKey, merged, existingCredential);
        Map<String, Object> card = buildMcpDriverCard(
                clientKey, merged, McpStore.mcpCredentialRef(clientKey),
                credential, existingCard);

        if (!asMap(credential.get("secrets")).isEmpty()) {
            McpStore.saveCredential(McpStore.mcpCredentialRef(clientKey), credential);
        } else {
            McpStore.deleteCredential(McpStore.mcpCredentialRef(clientKey));
        }
        McpStore.saveCard(clientKey, card);
        return buildInfoFromCard(card);
    }

    public McpModels.McpClientInfo toggleClient(String clientKey) {
        Map<String, Object> card = loadCard(clientKey);
        card.put("enabled", !asBool(card.get("enabled"), true));
        McpStore.saveCard(clientKey, card);
        return buildInfoFromCard(card);
    }

    public McpModels.McpMessageResponse deleteClient(String clientKey) {
        Map<String, Object> card = loadCard(clientKey);
        Map<String, Object> credentials = asMap(card.get("credentials"));
        Set<String> deleted = new java.util.HashSet<>();
        for (Map.Entry<String, Object> e : credentials.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> spec) {
                String ref = str(spec.get("ref"));
                if (!ref.isEmpty() && deleted.add(ref)) {
                    McpStore.deleteCredential(ref);
                }
            }
        }
        McpStore.deleteCredential(McpStore.mcpOauthCredentialRef(clientKey));
        McpStore.deleteCard(clientKey);
        return new McpModels.McpMessageResponse(
                "MCP client '" + clientKey + "' deleted successfully");
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /** Raise 400 if the key collides with reserved route prefixes. */
    public void validateClientKey(String clientKey) {
        String lower = clientKey == null ? "" : clientKey.toLowerCase();
        for (String prefix : RESERVED_KEY_PREFIXES) {
            String stripped = prefix.replaceAll("/$", "");
            if (lower.equals(stripped) || lower.startsWith(prefix)) {
                throw new McpException(400,
                        "MCP client key must not start with reserved prefix '" + prefix
                                + "'. Please choose a different key.");
            }
        }
    }

    public static String normalizeMcpDisplayName(String name, String fallback) {
        String value = name == null ? "" : name.strip();
        return value.isEmpty() ? fallback : value;
    }

    /** Ensure display names are unambiguous user-facing MCP identifiers. */
    public void ensureMcpDisplayNameUnique(String displayName, String clientKey) {
        String desired = displayNameKey(displayName);
        for (Map<String, Object> card : listCards()) {
            String cardName = str(card.get("name"));
            if (cardName.equals(clientKey)) continue;
            if (desired.equals(displayNameKey(cardName))) {
                throw new McpException(400,
                        "MCP client name '" + displayName + "' conflicts with "
                                + "existing MCP client key '" + cardName + "'.");
            }
            String existingDisplay = normalizeMcpDisplayName(
                    str(asMap(card.get("config")).get("display_name")), cardName);
            if (desired.equals(displayNameKey(existingDisplay))) {
                throw new McpException(400,
                        "MCP client name '" + displayName + "' already exists "
                                + "for MCP client '" + cardName + "'.");
            }
        }
    }

    private static String displayNameKey(String value) {
        return value == null ? "" : value.strip().toLowerCase();
    }

    /** Merge partial updates onto existing client info (port of config_service). */
    static McpModels.McpClientData mergeUpdateWithExisting(
            McpModels.McpClientInfo existing, McpModels.McpClientUpdateRequest updates) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", existing.name());
        data.put("description", existing.description());
        data.put("enabled", existing.enabled());
        data.put("transport", existing.transport());
        data.put("url", existing.url());
        data.put("headers", existing.headers());
        data.put("command", existing.command());
        data.put("args", existing.args());
        data.put("env", existing.env());
        data.put("cwd", existing.cwd());
        data.put("tools", existing.tools());

        applyIfNotNull(data, "name", updates.name());
        applyIfNotNull(data, "description", updates.description());
        applyIfNotNull(data, "enabled", updates.enabled());
        applyIfNotNull(data, "transport", updates.transport());
        applyIfNotNull(data, "url", updates.url());
        applyIfNotNull(data, "headers", updates.headers());
        applyIfNotNull(data, "command", updates.command());
        applyIfNotNull(data, "args", updates.args());
        applyIfNotNull(data, "env", updates.env());
        applyIfNotNull(data, "cwd", updates.cwd());
        applyIfNotNull(data, "tools", updates.tools());

        return new McpModels.McpClientData(
                str(data.get("name")),
                str(data.get("description")),
                (Boolean) data.get("enabled"),
                str(data.get("transport")),
                str(data.get("url")),
                asStringMap(data.get("headers")),
                str(data.get("command")),
                asStringList(data.get("args")),
                asStringMap(data.get("env")),
                str(data.get("cwd")),
                asStringListOrNull(data.get("tools")));
    }

    private static void applyIfNotNull(Map<String, Object> data, String key, Object value) {
        if (value != null) data.put(key, value);
    }

    // ------------------------------------------------------------------
    // Credential + card builders (port of mcp_card_builder.py)
    // ------------------------------------------------------------------

    /** Build the static MCP credential record from console request data. */
    Map<String, Object> buildMcpCredentialRecord(
            String clientKey,
            McpModels.McpClientData client,
            Map<String, Object> existing) {
        Map<String, String> incomingEnv = asStringMap(client.env());
        Map<String, String> incomingHeaders = asStringMap(client.headers());
        Map<String, String> existingSecrets = secretsOf(existing);
        Map<String, String> secrets = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : incomingEnv.entrySet()) {
            String key = String.valueOf(e.getKey());
            String value = String.valueOf(e.getValue());
            if ("public".equals(McpBinding.classifyMcpBinding("env", key, value))) {
                continue;
            }
            secrets.put(key, McpBinding.restoreMaskedValue(
                    value, existingSecrets.getOrDefault(key, "")));
        }

        Set<String> used = new java.util.HashSet<>(secrets.keySet());
        for (Map.Entry<String, String> e : incomingHeaders.entrySet()) {
            String header = String.valueOf(e.getKey());
            String value = String.valueOf(e.getValue());
            if ("public".equals(McpBinding.classifyMcpBinding("headers", header, value))) {
                continue;
            }
            String secretKey = McpBinding.normalizeSecretKey(header, used);
            used.add(secretKey);
            String oldValue = existingSecrets.getOrDefault(secretKey, "");
            secrets.put(secretKey, McpBinding.restoreMaskedValue(value, oldValue));
        }

        double now = System.currentTimeMillis() / 1000.0;
        Map<String, Object> meta = new LinkedHashMap<>();
        if (existing != null) {
            Map<String, Object> existingMeta = asMap(existing.get("meta"));
            if (!existingMeta.isEmpty()) meta.putAll(existingMeta);
        } else {
            meta.put("created_at", now);
        }
        meta.put("updated_at", now);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ref", McpStore.mcpCredentialRef(clientKey));
        record.put("kind", CREDENTIAL_KIND_STATIC);
        record.put("public", new LinkedHashMap<String, Object>());
        record.put("secrets", secrets);
        record.put("meta", meta);
        return record;
    }

    /** Build a DriverCard-style JSON map from create/update request data. */
    Map<String, Object> buildMcpDriverCard(
            String clientKey,
            McpModels.McpClientData client,
            String credentialRef,
            Map<String, Object> credentialRecord,
            Map<String, Object> existing) {
        Map<String, Object> current = existing == null
                ? new LinkedHashMap<>() : cardToClientData(existing);
        Map<String, Object> updates = clientDataToMap(client);
        Map<String, Object> data = new LinkedHashMap<>(current);
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            if (e.getValue() != null) data.put(e.getKey(), e.getValue());
        }

        String transport = str(data.get("transport"));
        if (transport.isEmpty()) transport = TRANSPORT_STDIO;
        Map<String, String> secrets = secretsOf(credentialRecord);

        Map<String, Object> endpoint = new LinkedHashMap<>();
        if (TRANSPORT_STDIO.equals(transport)) {
            Map.Entry<Map<String, String>, Map<String, String>> split =
                    McpBinding.splitMcpBinding("env", asStringMap(data.get("env")));
            Map<String, String> secretRefs = new LinkedHashMap<>();
            for (String key : split.getValue().keySet()) secretRefs.put(key, key);
            endpoint.put("transport", TRANSPORT_STDIO);
            endpoint.put("command", str(data.get("command")));
            endpoint.put("args", asStringList(data.get("args")));
            endpoint.put("env", McpBinding.sourceBindingFromSplit(
                    split.getKey(), secretRefs, CREDENTIAL_ALIAS_STATIC));
            String cwd = str(data.get("cwd"));
            if (!cwd.isEmpty()) endpoint.put("cwd", cwd);
        } else {
            Map.Entry<Map<String, String>, Map<String, String>> split =
                    McpBinding.splitMcpBinding("headers", asStringMap(data.get("headers")));
            Map<String, String> secretRefs = new LinkedHashMap<>();
            Set<String> used = new java.util.HashSet<>();
            for (String header : split.getValue().keySet()) {
                String secretKey = McpBinding.normalizeSecretKey(header, used);
                used.add(secretKey);
                secretRefs.put(header, secretKey);
            }
            endpoint.put("transport", transport);
            endpoint.put("url", str(data.get("url")));
            endpoint.put("headers", McpBinding.sourceBindingFromSplit(
                    split.getKey(), secretRefs, CREDENTIAL_ALIAS_STATIC));
            preserveOauthAuthorizationBinding(existing, endpoint);
        }

        Map<String, Object> credentials = new LinkedHashMap<>();
        if (existing != null) credentials.putAll(asMap(existing.get("credentials")));
        if (!secrets.isEmpty()) {
            Map<String, Object> staticSpec = new LinkedHashMap<>();
            staticSpec.put("kind", CREDENTIAL_KIND_STATIC);
            staticSpec.put("ref", credentialRef);
            credentials.put(CREDENTIAL_ALIAS_STATIC, staticSpec);
        } else {
            credentials.remove(CREDENTIAL_ALIAS_STATIC);
        }

        // Console-created MCP clients default to "ask".
        Map<String, Object> policy = existing != null && existing.get("policy") != null
                ? asMap(existing.get("policy"))
                : newPolicy(POLICY_EFFECT_ASK);

        Map<String, Object> config = new LinkedHashMap<>();
        String name = str(data.get("name")).strip();
        config.put("display_name", name.isEmpty() ? clientKey : name);
        config.put("description", str(data.get("description")));
        config.put("tools", data.get("tools"));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", clientKey);
        card.put("protocol", "mcp");
        card.put("endpoint", endpoint);
        card.put("credentials", credentials);
        card.put("config", config);
        card.put("enabled", data.containsKey("enabled")
                ? asBool(data.get("enabled"), true) : true);
        card.put("policy", policy);
        return card;
    }

    private static Map<String, Object> newPolicy(String defaultEffect) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("default_effect", defaultEffect);
        policy.put("rules", new ArrayList<Object>());
        return policy;
    }

    /** Port of card_builder._card_to_client_data. */
    private static Map<String, Object> cardToClientData(Map<String, Object> card) {
        Map<String, Object> endpoint = asMap(card.get("endpoint"));
        Map<String, Object> config = asMap(card.get("config"));
        Map<String, String> staticSecrets = Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        String name = str(config.get("display_name"));
        if (name.isEmpty()) name = str(card.get("name"));
        result.put("name", name);
        result.put("description", str(config.get("description")));
        result.put("enabled", asBool(card.get("enabled"), true));
        String transport = str(endpoint.get("transport"));
        result.put("transport", transport.isEmpty() ? TRANSPORT_STDIO : transport);
        result.put("url", str(endpoint.get("url")));
        result.put("headers", McpBinding.bindingPlainKeys(
                asMap(endpoint.get("headers")), CREDENTIAL_ALIAS_STATIC));
        result.put("command", str(endpoint.get("command")));
        result.put("args", stringList(endpoint.get("args")));
        result.put("env", McpBinding.bindingPlainKeys(
                asMap(endpoint.get("env")), CREDENTIAL_ALIAS_STATIC));
        result.put("cwd", str(endpoint.get("cwd")));
        return result;
    }

    /** Port of card_builder._preserve_oauth_authorization_binding. */
    private static void preserveOauthAuthorizationBinding(
            Map<String, Object> existing, Map<String, Object> endpoint) {
        if (existing == null) return;
        Map<String, Object> credentials = asMap(existing.get("credentials"));
        if (!credentials.containsKey(McpModels.CREDENTIAL_ALIAS_OAUTH)) return;
        Map<String, Object> headers = asMap(endpoint.get("headers"));
        if (headers.containsKey("Authorization")) return;
        Map<String, Object> existingEndpoint = asMap(existing.get("endpoint"));
        Object existingAuth = asMap(existingEndpoint.get("headers")).get("Authorization");
        if (existingAuth instanceof Map<?, ?> auth
                && "credential".equals(str(auth.get("source")))
                && McpModels.CREDENTIAL_ALIAS_OAUTH.equals(str(auth.get("credential")))) {
            headers.put("Authorization", existingAuth);
            endpoint.put("headers", headers);
        }
    }

    private static Map<String, Object> clientDataToMap(McpModels.McpClientData client) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", client.name());
        map.put("description", client.description());
        map.put("enabled", client.enabled());
        map.put("transport", client.transport());
        map.put("url", client.url());
        map.put("headers", client.headers());
        map.put("command", client.command());
        map.put("args", client.args());
        map.put("env", client.env());
        map.put("cwd", client.cwd());
        map.put("tools", client.tools());
        return map;
    }

    /** Port of config_service._credential_ref_by_alias_or_kind. */
    private static Map<String, Object> credentialRefByAliasOrKind(
            Map<String, Object> credentials, String alias, String kind) {
        Object direct = credentials.get(alias);
        if (direct instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) direct).entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        for (Object value : credentials.values()) {
            if (value instanceof Map<?, ?> spec
                    && kind.equals(str(spec.get("kind")))) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : spec.entrySet()) {
                    result.put(String.valueOf(e.getKey()), e.getValue());
                }
                return result;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Value helpers
    // ------------------------------------------------------------------

    static Map<String, String> secretsOf(Map<String, Object> record) {
        Map<String, String> result = new LinkedHashMap<>();
        if (record == null) return result;
        Map<String, Object> secrets = asMap(record.get("secrets"));
        for (Map.Entry<String, Object> e : secrets.entrySet()) {
            result.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) result.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return result;
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static boolean asBool(Object value, boolean def) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    static double toDouble(Object value, double def) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) result.add(String.valueOf(o));
        }
        return result;
    }

    static List<String> stringListOrNull(Object value) {
        if (value == null) return null;
        return stringList(value);
    }

    static Map<String, String> asStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) result.add(String.valueOf(o));
        }
        return result;
    }

    private static List<String> asStringListOrNull(Object value) {
        if (value == null) return null;
        return asStringList(value);
    }
}
