package com.agent.coding.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shell evasion guardian — detects attempts to smuggle dangerous payloads
 * past a plain command parser. Mirrors QwenPaw's ShellEvasionGuardian:
 * each category maps to a {@code security.tool_guard.shell_evasion_checks}
 * flag and is evaluated only when enabled.
 *
 * <p>Findings are returned with a severity; the caller decides whether to
 * block (HIGH+). LOW findings are informational (pipes, env vars, quoting
 * are common in legitimate commands).
 */
public final class ShellEvasionGuardian {

    public enum Severity { LOW, HIGH, CRITICAL }

    /** One detection finding. */
    public record Finding(String category, Severity severity, String message) {}

    // ── Detectors (compile-once) ─────────────────────────────────────

    /** Null byte / NUL smuggling — always fatal. */
    private static final Pattern NULL_BYTE = Pattern.compile("\\x00");

    /** Command substitution: $(...) or backticks. */
    private static final Pattern COMMAND_SUBSTITUTION = Pattern.compile(
            "\\$\\([^)]*\\)|`[^`]*`");

    /** Semicolon chained into a sensitive command. */
    private static final Pattern SEMICOLON_CHAIN = Pattern.compile(
            ";\\s*(?:sudo|rm|mkfs|dd|curl|wget|shutdown|reboot|halt|poweroff|chmod|mv|cp)");

    /** AND/OR chain into a sensitive command. */
    private static final Pattern AND_OR_CHAIN = Pattern.compile(
            "(?:&&|\\|\\|)\\s*(?:sudo|rm|mkfs|dd|curl|wget|shutdown|reboot|halt|poweroff|chmod)");

    /** Plain command chaining (informational). */
    private static final Pattern CHAINING = Pattern.compile("(?:&&|\\|\\||;\\s)");

    /** Input/output redirection to a device or system path. */
    private static final Pattern REDIRECT_DANGEROUS = Pattern.compile(
            "[<>]\\s*/(?:dev/(?:sda|sdb|sdc|mem|zero)|etc/(?:passwd|shadow|sudoers))\\b");

    /** Generic redirection (informational). */
    private static final Pattern REDIRECT = Pattern.compile("(?:[12]?>>?|<<)");

    /** Environment variable expansion (informational). */
    private static final Pattern ENV_VAR = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");

    /** Unusual quoting. */
    private static final Pattern QUOTING = Pattern.compile("''|'' ''|'[^']{2,}'\\s*'[^']{2,}'");

    /** Shell metacharacters (informational). */
    private static final Pattern METACHARS = Pattern.compile("[;&|<>$()*?{}\\[\\]!~]");

    /** Whitespace variants / control chars. */
    private static final Pattern WHITESPACE_VARIANTS = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u00A0]");

    /** Which category a finding belongs to (config key). */
    private static final Map<String, String> CATEGORY_BY_NAME = Map.of(
            "null_byte", "null_byte",
            "command_substitution", "command_substitution",
            "semantic_injection", "semantic_injection",
            "input_redirection", "input_redirection",
            "environment_variable", "environment_variable",
            "quoting", "quoting",
            "shell_metacharacters", "shell_metacharacters",
            "whitespace_variants", "whitespace_variants");

    private ShellEvasionGuardian() {}

    /**
     * Scan a shell command for evasion findings.
     *
     * @param command  the shell command string
     * @param checks   enabled flags keyed by category (from
     *                 {@code security.tool_guard.shell_evasion_checks});
     *                 null means all enabled
     */
    public static List<Finding> scan(String command, Map<String, Object> checks) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();

        if (enabled(checks, "null_byte") && NULL_BYTE.matcher(command).find()) {
            findings.add(new Finding("null_byte", Severity.CRITICAL,
                    "NUL byte present in command"));
        }
        if (enabled(checks, "command_substitution") && COMMAND_SUBSTITUTION.matcher(command).find()) {
            findings.add(new Finding("command_substitution", Severity.HIGH,
                    "Command substitution ($(...) or backticks) detected"));
        }
        if (enabled(checks, "semantic_injection")) {
            if (SEMICOLON_CHAIN.matcher(command).find() || AND_OR_CHAIN.matcher(command).find()) {
                findings.add(new Finding("semantic_injection", Severity.HIGH,
                        "Command chaining into a sensitive command detected"));
            } else if (CHAINING.matcher(command).find()) {
                findings.add(new Finding("semantic_injection", Severity.LOW,
                        "Command chaining present"));
            }
        }
        if (enabled(checks, "input_redirection")) {
            if (REDIRECT_DANGEROUS.matcher(command).find()) {
                findings.add(new Finding("input_redirection", Severity.HIGH,
                        "Redirection to a system device/path detected"));
            } else if (REDIRECT.matcher(command).find()) {
                findings.add(new Finding("input_redirection", Severity.LOW,
                        "Output redirection present"));
            }
        }
        if (enabled(checks, "environment_variable") && ENV_VAR.matcher(command).find()) {
            findings.add(new Finding("environment_variable", Severity.LOW,
                    "Environment variable expansion present"));
        }
        if (enabled(checks, "quoting") && QUOTING.matcher(command).find()) {
            findings.add(new Finding("quoting", Severity.LOW,
                    "Unusual quoting detected"));
        }
        if (enabled(checks, "shell_metacharacters") && METACHARS.matcher(command).find()) {
            findings.add(new Finding("shell_metacharacters", Severity.LOW,
                    "Shell metacharacters present"));
        }
        if (enabled(checks, "whitespace_variants") && WHITESPACE_VARIANTS.matcher(command).find()) {
            findings.add(new Finding("whitespace_variants", Severity.LOW,
                    "Control/whitespace variants detected"));
        }
        return findings;
    }

    private static boolean enabled(Map<String, Object> checks, String key) {
        Object v = checks == null ? null : checks.get(key);
        return v == null || Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** Whether a finding must block execution. */
    public static boolean isBlocking(Finding f) {
        return f.severity() == Severity.CRITICAL || f.severity() == Severity.HIGH;
    }

    /** Config key for a finding category (for UI). */
    public static String configKey(Finding f) {
        return CATEGORY_BY_NAME.getOrDefault(f.category(), f.category());
    }
}
