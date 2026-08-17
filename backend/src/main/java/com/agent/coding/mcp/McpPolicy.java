package com.agent.coding.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.agent.coding.mcp.McpModels.CAPABILITY_KIND_TOOL;
import static com.agent.coding.mcp.McpModels.MCP_EFFECTS;
import static com.agent.coding.mcp.McpModels.POLICY_EFFECT_ASK;
import static com.agent.coding.mcp.McpModels.POLICY_TARGET_WILDCARD;
import static com.agent.coding.mcp.McpModels.PRINCIPAL_SOURCE_CHANNEL;
import static com.agent.coding.mcp.McpModels.PRINCIPAL_SUBJECT_ALL;
import static com.agent.coding.mcp.McpModels.PRINCIPAL_SUBJECT_USER;

/**
 * DriverPolicy &lt;-&gt; MCPAccessPolicy mapping logic.
 *
 * <p>. py policy presentation code
 * (mcp_access_policy_from_card / driver_policy_from_mcp_access_update and
 * the rule classifiers). Policies are stored inside the client card as
 * {@code {"default_effect": ..., "rules": [...]}}.</p>
 */
public final class McpPolicy {

    private McpPolicy() {}

    // ------------------------------------------------------------------
    // Rule accessors (operate on rule maps)
    // ------------------------------------------------------------------

    private static String ruleSubject(Map<String, Object> rule) {
        return str(rule.get("subject"));
    }

    private static String ruleEffect(Map<String, Object> rule) {
        return str(rule.get("effect"));
    }

    private static Object ruleCondition(Map<String, Object> rule) {
        return rule.get("condition");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ruleTarget(Map<String, Object> rule) {
        Object target = rule.get("target");
        if (target instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rulePrincipal(Map<String, Object> rule) {
        Object principal = rule.get("principal");
        if (principal instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // Access summary helper (overrides_count)
    // ------------------------------------------------------------------

    /** Mirror of card_builder._is_tool_access_override. */
    public static boolean isToolAccessOverride(Map<String, Object> rule) {
        if (rule == null) return false;
        if (ruleCondition(rule) != null) return false;
        Map<String, Object> target = ruleTarget(rule);
        if (!CAPABILITY_KIND_TOOL.equals(str(target.get("kind")))) return false;
        if (str(target.get("name")).isEmpty()) return false;
        if (!MCP_EFFECTS.contains(ruleEffect(rule))) return false;
        Map<String, Object> principal = rulePrincipal(rule);
        String sourceType = str(principal.get("source_type")).strip().toLowerCase();
        String subjectType = str(principal.get("subject_type")).strip().toLowerCase();
        if (PRINCIPAL_SOURCE_CHANNEL.equals(sourceType)
                && (PRINCIPAL_SUBJECT_ALL.equals(subjectType)
                || PRINCIPAL_SUBJECT_USER.equals(subjectType))) {
            return true;
        }
        String subject = ruleSubject(rule).strip();
        return subject.equals(POLICY_TARGET_WILDCARD)
                || subject.startsWith("channel:")
                || subject.startsWith("user:");
    }

    /** Count console-managed tool access override rules for a policy. */
    public static int countToolAccessOverrides(Map<String, Object> policy) {
        int count = 0;
        for (Map<String, Object> rule : rulesOf(policy)) {
            if (isToolAccessOverride(rule)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Card -> MCPAccessPolicy
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rulesOf(Map<String, Object> policy) {
        List<Map<String, Object>> rules = new ArrayList<>();
        if (policy == null) return rules;
        Object raw = policy.get("rules");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> rule = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        rule.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    public static String defaultEffectOf(Map<String, Object> policy) {
        if (policy == null) return POLICY_EFFECT_ASK;
        String effect = str(policy.get("default_effect"));
        if (effect.isEmpty() || !MCP_EFFECTS.contains(effect)) return POLICY_EFFECT_ASK;
        return effect;
    }

    /** Port of mcp_access_policy_from_card. */
    public static McpModels.McpAccessPolicy accessPolicyFromCard(Map<String, Object> policy) {
        List<McpModels.McpAccessRule> clientOverrides = new ArrayList<>();
        List<McpModels.McpToolDefaultPolicy> toolDefaults = new ArrayList<>();
        List<McpModels.McpToolAccessOverride> toolOverrides = new ArrayList<>();
        int unmanaged = 0;
        for (Map<String, Object> rule : rulesOf(policy)) {
            McpModels.McpAccessRule clientOverride = mcpClientOverrideFromRule(rule);
            McpModels.McpToolDefaultPolicy toolDefault = mcpToolDefaultFromRule(rule);
            McpModels.McpToolAccessOverride toolOverride = mcpToolOverrideFromRule(rule);
            if (clientOverride != null) clientOverrides.add(clientOverride);
            if (toolDefault != null) toolDefaults.add(toolDefault);
            if (toolOverride != null) toolOverrides.add(toolOverride);
            if (clientOverride == null && toolDefault == null && toolOverride == null) {
                unmanaged++;
            }
        }
        return new McpModels.McpAccessPolicy(
                defaultEffectOf(policy),
                clientOverrides,
                toolDefaults,
                toolOverrides,
                unmanaged);
    }

    /** Port of _mcp_client_override_from_rule. */
    static McpModels.McpAccessRule mcpClientOverrideFromRule(Map<String, Object> rule) {
        if (rule == null) return null;
        Map<String, Object> target = ruleTarget(rule);
        String kind = str(target.get("kind"));
        String name = str(target.get("name"));
        if (POLICY_TARGET_WILDCARD.equals(kind) && POLICY_TARGET_WILDCARD.equals(name)) {
            if (ruleCondition(rule) != null
                    || !MCP_EFFECTS.contains(ruleEffect(rule))) {
                return null;
            }
            return legacySubjectAccessRule(rule);
        }
        if (!CAPABILITY_KIND_TOOL.equals(kind)
                || !POLICY_TARGET_WILDCARD.equals(name)) {
            return null;
        }
        return mcpAccessRuleFromRule(rule);
    }

    /** Port of _mcp_tool_default_from_rule. */
    static McpModels.McpToolDefaultPolicy mcpToolDefaultFromRule(Map<String, Object> rule) {
        if (!isMcpToolDefaultRule(rule)) return null;
        return new McpModels.McpToolDefaultPolicy(
                str(ruleTarget(rule).get("name")),
                ruleEffect(rule));
    }

    /** Port of _mcp_tool_override_from_rule. */
    static McpModels.McpToolAccessOverride mcpToolOverrideFromRule(Map<String, Object> rule) {
        if (rule == null) return null;
        Map<String, Object> target = ruleTarget(rule);
        String kind = str(target.get("kind"));
        String name = str(target.get("name"));
        if (!CAPABILITY_KIND_TOOL.equals(kind)
                || name.isEmpty()
                || POLICY_TARGET_WILDCARD.equals(name)
                || isMcpToolDefaultRule(rule)) {
            return null;
        }
        McpModels.McpAccessRule accessRule = mcpAccessRuleFromRule(rule);
        if (accessRule == null) return null;
        return new McpModels.McpToolAccessOverride(
                accessRule.sourceType(),
                accessRule.sourceValue(),
                accessRule.subjectType(),
                accessRule.subjectValue(),
                accessRule.effect(),
                name);
    }

    /** Port of _mcp_access_rule_from_rule. */
    static McpModels.McpAccessRule mcpAccessRuleFromRule(Map<String, Object> rule) {
        if (rule == null) return null;
        if (ruleCondition(rule) != null
                || !CAPABILITY_KIND_TOOL.equals(str(ruleTarget(rule).get("kind")))
                || str(ruleTarget(rule).get("name")).isEmpty()
                || !MCP_EFFECTS.contains(ruleEffect(rule))) {
            return null;
        }
        Map<String, Object> principal = rulePrincipal(rule);
        String sourceType = str(principal.get("source_type")).strip().toLowerCase();
        String sourceValue = str(principal.get("source_value")).strip();
        String subjectType = str(principal.get("subject_type")).strip().toLowerCase();
        String subjectValue = str(principal.get("subject_value")).strip();
        if (PRINCIPAL_SOURCE_CHANNEL.equals(sourceType)
                && (PRINCIPAL_SUBJECT_ALL.equals(subjectType)
                || PRINCIPAL_SUBJECT_USER.equals(subjectType))) {
            return new McpModels.McpAccessRule(
                    sourceType,
                    sourceValue,
                    subjectType,
                    PRINCIPAL_SUBJECT_ALL.equals(subjectType) ? "" : subjectValue,
                    ruleEffect(rule));
        }
        return legacySubjectAccessRule(rule);
    }

    /** Port of _is_mcp_tool_default_rule. */
    static boolean isMcpToolDefaultRule(Map<String, Object> rule) {
        if (rule == null) return false;
        if (ruleCondition(rule) != null) return false;
        Map<String, Object> target = ruleTarget(rule);
        String name = str(target.get("name"));
        if (!CAPABILITY_KIND_TOOL.equals(str(target.get("kind")))
                || name.isEmpty()
                || POLICY_TARGET_WILDCARD.equals(name)
                || !MCP_EFFECTS.contains(ruleEffect(rule))
                || !POLICY_TARGET_WILDCARD.equals(ruleSubject(rule))) {
            return false;
        }
        Map<String, Object> principal = rulePrincipal(rule);
        return isBlankOrWildcard(str(principal.get("source_type")))
                && isBlankOrWildcard(str(principal.get("source_value")))
                && isBlankOrWildcard(str(principal.get("subject_type")))
                && isBlankOrWildcard(str(principal.get("subject_value")));
    }

    /** Port of _legacy_subject_access_rule. */
    static McpModels.McpAccessRule legacySubjectAccessRule(Map<String, Object> rule) {
        String subject = ruleSubject(rule).strip();
        if (subject.isEmpty()) return null;
        String effect = ruleEffect(rule);
        if (POLICY_TARGET_WILDCARD.equals(subject)) {
            return new McpModels.McpAccessRule(
                    PRINCIPAL_SOURCE_CHANNEL, "console",
                    PRINCIPAL_SUBJECT_ALL, "", effect);
        }
        if (subject.startsWith("channel:")) {
            String value = subject.substring("channel:".length());
            return new McpModels.McpAccessRule(
                    PRINCIPAL_SOURCE_CHANNEL,
                    value.isEmpty() ? POLICY_TARGET_WILDCARD : value,
                    PRINCIPAL_SUBJECT_ALL, "", effect);
        }
        if (subject.startsWith("user:")) {
            String user = subject.substring("user:".length());
            boolean wildcard = POLICY_TARGET_WILDCARD.equals(user);
            return new McpModels.McpAccessRule(
                    PRINCIPAL_SOURCE_CHANNEL, "console",
                    wildcard ? PRINCIPAL_SUBJECT_ALL : PRINCIPAL_SUBJECT_USER,
                    wildcard ? "" : user,
                    effect);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // MCPAccessPolicy -> DriverPolicy
    // ------------------------------------------------------------------

    /** Port of driver_policy_from_mcp_access_update. */
    public static Map<String, Object> policyFromAccessUpdate(
            Map<String, Object> existing,
            McpModels.McpAccessPolicy access) {
        List<Map<String, Object>> unmanaged = new ArrayList<>();
        if (existing != null) {
            for (Map<String, Object> rule : rulesOf(existing)) {
                if (!isConsoleManagedMcpPolicyRule(rule)) {
                    unmanaged.add(rule);
                }
            }
        }
        Set<String> seenDefaults = new HashSet<>();
        Set<String> seenRules = new HashSet<>();
        List<Map<String, Object>> managed = new ArrayList<>();

        for (McpModels.McpToolDefaultPolicy def : access.toolDefaults()) {
            String toolName = def.toolName() == null ? "" : def.toolName().strip();
            if (toolName.isEmpty() || POLICY_TARGET_WILDCARD.equals(toolName)) {
                throw new McpException(400, "MCP tool default name is empty");
            }
            if (seenDefaults.contains(toolName)) continue;
            seenDefaults.add(toolName);
            managed.add(buildRule(POLICY_TARGET_WILDCARD, def.effect(),
                    toolName, emptyPrincipal()));
        }

        for (McpModels.McpAccessRule override : access.clientOverrides()) {
            managed.add(buildOverrideRule(POLICY_TARGET_WILDCARD, override, seenRules));
        }
        for (McpModels.McpToolAccessOverride override : access.toolOverrides()) {
            String toolName = override.toolName() == null ? "" : override.toolName().strip();
            if (toolName.isEmpty()) {
                throw new McpException(400, "MCP tool override name is empty");
            }
            McpModels.McpAccessRule rule = new McpModels.McpAccessRule(
                    override.sourceType(), override.sourceValue(),
                    override.subjectType(), override.subjectValue(), override.effect());
            managed.add(buildOverrideRule(toolName, rule, seenRules));
        }

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("default_effect",
                access.defaultEffect() == null ? POLICY_EFFECT_ASK : access.defaultEffect());
        List<Map<String, Object>> rules = new ArrayList<>(unmanaged);
        rules.addAll(managed);
        policy.put("rules", rules);
        return policy;
    }

    private static Map<String, Object> buildOverrideRule(
            String targetName,
            McpModels.McpAccessRule override,
            Set<String> seenRules) {
        String sourceValue = override.sourceValue() == null ? "" : override.sourceValue().strip();
        String subjectValue = override.subjectValue() == null ? "" : override.subjectValue().strip();
        if (sourceValue.isEmpty()) {
            throw new McpException(400, "MCP policy source value is empty");
        }
        String subjectType = override.subjectType() == null
                ? PRINCIPAL_SUBJECT_ALL : override.subjectType();
        if (PRINCIPAL_SUBJECT_USER.equals(subjectType) && subjectValue.isEmpty()) {
            throw new McpException(400, "MCP policy user value is empty");
        }
        if (PRINCIPAL_SUBJECT_ALL.equals(subjectType)) {
            subjectValue = "";
        }
        String sourceType = override.sourceType() == null
                ? PRINCIPAL_SOURCE_CHANNEL : override.sourceType();
        String key = targetName + "\u0000" + sourceType + "\u0000" + sourceValue
                + "\u0000" + subjectType + "\u0000" + subjectValue;
        if (seenRules.contains(key)) return null;
        seenRules.add(key);

        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("source_type", sourceType);
        principal.put("source_value", sourceValue);
        principal.put("subject_type", subjectType);
        principal.put("subject_value", subjectValue);
        return buildRule(POLICY_TARGET_WILDCARD, override.effect(), targetName, principal);
    }

    private static Map<String, Object> buildRule(
            String subject,
            String effect,
            String targetName,
            Map<String, Object> principal) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("kind", CAPABILITY_KIND_TOOL);
        target.put("name", targetName);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("subject", subject);
        rule.put("effect", effect);
        rule.put("target", target);
        rule.put("principal", principal);
        rule.put("condition", null);
        return rule;
    }

    private static Map<String, Object> emptyPrincipal() {
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("source_type", "");
        principal.put("source_value", "");
        principal.put("subject_type", "");
        principal.put("subject_value", "");
        return principal;
    }

    /** Port of _is_console_managed_mcp_policy_rule. */
    static boolean isConsoleManagedMcpPolicyRule(Map<String, Object> rule) {
        return mcpClientOverrideFromRule(rule) != null
                || mcpToolDefaultFromRule(rule) != null
                || mcpToolOverrideFromRule(rule) != null;
    }

    private static boolean isBlankOrWildcard(String value) {
        String v = value == null ? "" : value.strip();
        return v.isEmpty() || POLICY_TARGET_WILDCARD.equals(v);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
