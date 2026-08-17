package com.agent.coding.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP env/header binding classification and presentation helpers.
 *
 * <p>. Decides which env /
 * header values are stored literally in the card ({@code public}) vs.
 * persisted in the credential record and masked in API responses
 * ({@code secret}), plus masking/restoration of secret values.</p>
 */
public final class McpBinding {

    private McpBinding() {}

    public static final Set<String> PUBLIC_HEADER_KEYS = Set.of(
            "accept", "content-type", "user-agent", "x-client-name");

    public static final Set<String> SECRET_HEADER_KEYS = Set.of(
            "authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-auth-token");

    public static final Set<String> PUBLIC_ENV_KEYS = Set.of(
            "NODE_ENV", "LOG_LEVEL", "DEBUG", "MCP_MODE");

    public static final List<String> SECRET_ENV_KEY_PARTS = List.of(
            "KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH");

    private static final java.util.regex.Pattern SAFE_KEY_PATTERN =
            java.util.regex.Pattern.compile("[^a-z0-9_]+");

    /** Return a lowercase credential secret key for an env/header name. */
    public static String normalizeSecretKey(String name, Set<String> existing) {
        String base = SAFE_KEY_PATTERN.matcher(name.trim().toLowerCase())
                .replaceAll("_");
        while (base.startsWith("_")) base = base.substring(1);
        while (base.endsWith("_")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) base = "secret";
        if (existing == null || !existing.contains(base)) return base;
        int index = 2;
        while (existing.contains(base + "_" + index)) index++;
        return base + "_" + index;
    }

    /** Classify one Console MCP env/header value as "public" or "secret". */
    public static String classifyMcpBinding(String section, String key, String value) {
        if ("headers".equals(section)) {
            String lowered = key == null ? "" : key.trim().toLowerCase();
            if (SECRET_HEADER_KEYS.contains(lowered)) return "secret";
            if (PUBLIC_HEADER_KEYS.contains(lowered)) return "public";
            return "secret";
        }
        if ("env".equals(section)) {
            String stripped = key == null ? "" : key.trim();
            String upper = stripped.toUpperCase();
            for (String part : SECRET_ENV_KEY_PARTS) {
                if (upper.contains(part)) return "secret";
            }
            if (PUBLIC_ENV_KEYS.contains(stripped) || PUBLIC_ENV_KEYS.contains(upper)) {
                return "public";
            }
            return "secret";
        }
        return "secret";
    }

    /** Split an env/header map into (public literals, secret values). */
    public static Map.Entry<Map<String, String>, Map<String, String>> splitMcpBinding(
            String section, Map<String, String> values) {
        Map<String, String> publicVals = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        if (values == null) return Map.entry(publicVals, secrets);
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = String.valueOf(e.getKey());
            String value = String.valueOf(e.getValue());
            if ("public".equals(classifyMcpBinding(section, key, value))) {
                publicVals.put(key, value);
            } else {
                secrets.put(key, value);
            }
        }
        return Map.entry(publicVals, secrets);
    }

    /**
     * Build canonical binding entries: public keys become literal specs,
     * secret keys become credential specs pointing at the credential field.
     */
    public static Map<String, Object> sourceBindingFromSplit(
            Map<String, String> publicVals,
            Map<String, String> secretRefs,
            String credentialAlias) {
        Map<String, Object> binding = new LinkedHashMap<>();
        if (publicVals != null) {
            for (Map.Entry<String, String> e : publicVals.entrySet()) {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("source", "literal");
                spec.put("value", String.valueOf(e.getValue()));
                binding.put(String.valueOf(e.getKey()), spec);
            }
        }
        if (secretRefs != null) {
            for (Map.Entry<String, String> e : secretRefs.entrySet()) {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("source", "credential");
                spec.put("credential", credentialAlias);
                spec.put("field", String.valueOf(e.getValue()));
                binding.put(String.valueOf(e.getKey()), spec);
            }
        }
        return binding;
    }

    /** Return masked Console response values from an endpoint binding. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> bindingToResponse(
            Map<String, Object> binding,
            Map<String, String> secrets,
            String credentialAlias) {
        Map<String, String> result = new LinkedHashMap<>();
        if (binding == null || binding.isEmpty()) return result;
        if (!binding.containsKey("public") && !binding.containsKey("secret_refs")) {
            for (Map.Entry<String, Object> e : binding.entrySet()) {
                Object spec = e.getValue();
                if (spec instanceof Map<?, ?> m) {
                    String source = str(m.get("source"));
                    if ("literal".equals(source)) {
                        result.put(e.getKey(), str(m.get("value")));
                    } else if ("credential".equals(source)
                            && credentialAlias.equals(str(m.get("credential")))) {
                        String field = str(m.get("field"));
                        String value = secrets == null ? "" : secrets.getOrDefault(field, "");
                        result.put(e.getKey(), maskMcpSecretValue(value));
                    }
                } else {
                    result.put(e.getKey(), str(spec));
                }
            }
            return result;
        }
        // Legacy {public, secret_refs} form
        Object pub = binding.get("public");
        if (pub instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                result.put(str(e.getKey()), str(e.getValue()));
            }
        }
        Object refs = binding.get("secret_refs");
        if (refs instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                String secretKey = str(e.getValue());
                String value = secrets == null ? "" : secrets.getOrDefault(secretKey, "");
                result.put(str(e.getKey()), maskMcpSecretValue(value));
            }
        }
        return result;
    }

    /** Return unmasked public values and blank placeholders for secret keys. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> bindingPlainKeys(
            Map<String, Object> binding,
            String credentialAlias) {
        Map<String, String> result = new LinkedHashMap<>();
        if (binding == null || binding.isEmpty()) return result;
        if (!binding.containsKey("public") && !binding.containsKey("secret_refs")) {
            for (Map.Entry<String, Object> e : binding.entrySet()) {
                Object spec = e.getValue();
                if (spec instanceof Map<?, ?> m) {
                    String source = str(m.get("source"));
                    if ("literal".equals(source)) {
                        result.put(e.getKey(), str(m.get("value")));
                    } else if ("credential".equals(source)
                            && credentialAlias.equals(str(m.get("credential")))) {
                        result.put(e.getKey(), "");
                    }
                } else {
                    result.put(e.getKey(), str(spec));
                }
            }
            return result;
        }
        Object pub = binding.get("public");
        if (pub instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                result.put(str(e.getKey()), str(e.getValue()));
            }
        }
        Object refs = binding.get("secret_refs");
        if (refs instanceof Map<?, ?> rm) {
            for (Object key : rm.keySet()) {
                result.put(str(key), "");
            }
        }
        return result;
    }

    /** Return the existing secret when incoming equals its masked display. */
    public static String restoreMaskedValue(String incoming, String existing) {
        if (existing != null && !existing.isEmpty()
                && incoming != null && incoming.equals(maskMcpSecretValue(existing))) {
            return existing;
        }
        return incoming;
    }

    /** Mask a secret value for Console display (). */
    public static String maskMcpSecretValue(String value) {
        if (value == null || value.isEmpty()) return value;
        int length = value.length();
        if (length <= 8) return "*".repeat(length);
        if (length <= 12) {
            int mid = Math.max(length - 2, 4);
            return value.substring(0, 1) + "*".repeat(mid) + value.substring(length - 1);
        }
        int prefixLen = (length > 2 && value.charAt(2) == '-') ? 3 : 2;
        String prefix = value.substring(0, prefixLen);
        int suffixLen = length >= 16 ? 4 : 2;
        String suffix = value.substring(length - suffixLen);
        int maskedLen = Math.max(length - prefixLen - suffixLen, 4);
        return prefix + "*".repeat(maskedLen) + suffix;
    }

    // ------------------------------------------------------------------
    // Binding spec resolution (literal / credential / oauth bearer)
    // ------------------------------------------------------------------

    /** Resolve a binding spec to an actual value using given secret maps. */
    @SuppressWarnings("unchecked")
    public static String resolveBindingSpec(
            Object spec,
            Map<String, String> staticSecrets,
            Map<String, String> oauthSecrets) {
        if (spec == null) return "";
        if (!(spec instanceof Map<?, ?> m)) return str(spec);
        String source = str(m.get("source"));
        if ("literal".equals(source)) return str(m.get("value"));
        if ("credential".equals(source)) {
            String alias = str(m.get("credential"));
            String field = str(m.get("field"));
            String value = "";
            if (McpModels.CREDENTIAL_ALIAS_STATIC.equals(alias)) {
                value = staticSecrets == null ? "" : staticSecrets.getOrDefault(field, "");
            } else if (McpModels.CREDENTIAL_ALIAS_OAUTH.equals(alias)) {
                value = oauthSecrets == null ? "" : oauthSecrets.getOrDefault(field, "");
            }
            String format = str(m.get("format"));
            if (!format.isEmpty()) {
                return format.replace("{value}", value);
            }
            return value;
        }
        return "";
    }

    /** Merge resolved env bindings over a fresh map (stdio transport). */
    @SuppressWarnings("unchecked")
    public static Map<String, String> resolveEnvBinding(
            Map<String, Object> binding,
            Map<String, String> staticSecrets,
            Map<String, String> oauthSecrets) {
        Map<String, String> env = new LinkedHashMap<>();
        if (binding == null) return env;
        for (Map.Entry<String, Object> e : binding.entrySet()) {
            String value = resolveBindingSpec(e.getValue(), staticSecrets, oauthSecrets);
            if (value != null) env.put(e.getKey(), value);
        }
        return env;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                result.put(str(e.getKey()), e.getValue());
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) result.add(str(o));
        }
        return result;
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unused")
    static Map<String, Object> asMapSafe(Object value) {
        return asMap(value);
    }
}
