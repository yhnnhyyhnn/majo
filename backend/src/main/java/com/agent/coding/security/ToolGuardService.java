package com.agent.coding.security;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool Guard rule engine — the enforcement counterpart of the
 * {@code /api/config/security/tool-guard} configuration endpoints.
 *
 * <p>Every tool call that passes through {@link ToolGuardHook} is checked
 * against:
 * <ol>
 *   <li>{@code denied_tools} — hard deny list;</li>
 *   <li>built-in rules (minus {@code disabled_rules}) — destructive command
 *       patterns ported from QwenPaw's tool_guard rule set;</li>
 *   <li>user {@code custom_rules} — same schema as the UI;</li>
 *   <li>shell-evasion heuristics for shell tools
 *       ({@link ShellEvasionGuardian}).</li>
 * </ol>
 *
 * <p>Configuration is stored globally in {@code agents.json} under the
 * {@code security.tool_guard} key, shared with SecurityConfigController.
 */
@Service
public class ToolGuardService {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardService.class);

    /** Tools treated as shell command executors. */
    private static final List<String> SHELL_TOOLS =
            List.of("execute_command", "execute_shell_command", "bash", "shell");

    /** Tools whose primary parameter is a file path (File Guard applies). */
    public static final List<String> FILE_TOOLS =
            List.of("read_file", "write_file", "edit_file", "append_file");

    /** One built-in rule (mirrors the frontend ToolGuardRule schema). */
    public record BuiltinRule(
            String id,
            String category,
            String severity,
            List<String> tools,
            List<String> params,
            List<String> patterns,
            List<String> excludePatterns,
            String description,
            String remediation) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("tools", tools);
            m.put("params", params);
            m.put("category", category);
            m.put("severity", severity);
            m.put("patterns", patterns);
            m.put("exclude_patterns", excludePatterns);
            m.put("description", description);
            m.put("remediation", remediation);
            return m;
        }
    }

    /** Immutable compiled rule for matching. */
    private record CompiledRule(BuiltinRule def, List<Pattern> compiled) {
        boolean matches(Map<String, Object> input) {
            for (String param : def.params()) {
                Object v = input.get(param);
                if (v == null) continue;
                String text = String.valueOf(v);
                for (Pattern p : compiled) {
                    if (p.matcher(text).find()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ── Built-in rule catalog (ported from QwenPaw tool_guard rules) ──

    private static final List<BuiltinRule> BUILTIN_RULES = List.of(
            new BuiltinRule(
                    "SAFETY_CHECKS_DESTRUCTIVE_COMMAND", "safety", "CRITICAL",
                    SHELL_TOOLS, List.of("command"),
                    List.of(
                            "rm\\s+-[a-zA-Z]*r[a-zA-Z]*f?\\s+(?:/\\s*$|~/|\\*|/\\*|/?\\.)",
                            "rm\\s+-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*\\s+(?:/\\s*$|~/|\\*|/\\*|/?\\.)",
                            "mkfs(?:\\.\\w+)?(?:\\s|$)",
                            "dd\\s+[^|]*of\\s*=\\s*/dev/(?:sda|sdb|sdc|mem|zero)",
                            ":[(){}\\s|&;]+\\{\\s*:\\s*\\|\\s*:&\\s*}\\s*;\\s*:",
                            "chmod\\s+-R\\s+777\\s+/(?:\\s|$)",
                            "echo\\s+[^>]*>\\s*/etc/(?:passwd|shadow|sudoers)",
                            ">\\s*/dev/(?:sda|sdb|sdc|mem|zero)"),
                    List.of(),
                    "Destructive commands that can erase data or brick the system",
                    "Do not run destructive commands; ask the user for an explicit, reviewed command"),

            new BuiltinRule(
                    "COMMAND_PIPE_TO_SHELL", "injection", "HIGH",
                    SHELL_TOOLS, List.of("command"),
                    List.of(
                            "(?:curl|wget|fetch|powershell\\s+-?[a-z]*(?:c|enc))\\b[^|;]*\\|\\s*(?:sh|bash|zsh|ksh|dash|pwsh|powershell)\\b",
                            "\\b(?:sh|bash|zsh)\\b[^|;]*\\|\\s*(?:sh|bash|zsh)\\b"),
                    List.of(),
                    "Downloading and piping remote content directly into a shell",
                    "Download the file, inspect it, and only then execute it with explicit user approval"),

            new BuiltinRule(
                    "COMMAND_CHAIN_SENSITIVE", "injection", "HIGH",
                    SHELL_TOOLS, List.of("command"),
                    List.of(
                            "(?:&&|\\|\\||;)\\s*(?:rm|mkfs|dd|shutdown|reboot|halt|poweroff|chmod)\\b",
                            "\\b(?:shutdown|reboot|halt|poweroff)\\b"),
                    List.of("shutdown\\s+-h\\s+now.*(?:reboot|restart)"),
                    "Commands chained into system-level destructive operations",
                    "Split into separate, explicitly approved steps"),

            new BuiltinRule(
                    "PATH_TRAVERSAL", "path", "MEDIUM",
                    FILE_TOOLS, List.of("path"),
                    List.of("\\.\\.(?:/|\\\\)", "^\\.\\.", "^[A-Za-z]:[\\\\/]"),
                    List.of(),
                    "Path escaping the workspace root",
                    "Reject paths containing '..' or absolute drive paths"),

            new BuiltinRule(
                    "FILE_WRITE_SENSITIVE", "file", "HIGH",
                    List.of("write_file", "edit_file", "append_file"), List.of("path"),
                    List.of(
                            "(?:^|/)(?:\\.ssh|\\.aws|\\.env|id_rsa|id_ed25519|\\.bash_history|\\.git/config)(?:/|$)",
                            "/etc/(?:passwd|shadow|sudoers|fstab|hosts)",
                            "(?:^|/)(?:secrets?|credentials?|api[_-]?key)[^/]*\\.(?:json|ya?ml|toml|env|txt)(?:/|$)"),
                    List.of(),
                    "Writing to sensitive credential or system files",
                    "Never write to credential/system files; surface the content to the user instead"),

            new BuiltinRule(
                    "FILE_READ_SENSITIVE", "file", "MEDIUM",
                    List.of("read_file"), List.of("path"),
                    List.of("(?:^|/)(?:\\.ssh/id_rsa|\\.ssh/id_ed25519|id_rsa|id_ed25519|/etc/shadow)(?:/|$)"),
                    List.of(),
                    "Reading private keys or shadow files",
                    "Do not read private keys; redirect the model to a safe summary"),

            new BuiltinRule(
                    "SHELL_EVASION", "evasion", "HIGH",
                    SHELL_TOOLS, List.of("command"),
                    List.of("\\x00", "\\$\\([^)]*\\)", "`[^`]*`"),
                    List.of(),
                    "Shell evasion primitives (NUL byte, command substitution)",
                    "Never obfuscate commands; run them verbatim with user approval")
    );

    private static final List<CompiledRule> COMPILED = BUILTIN_RULES.stream()
            .map(r -> new CompiledRule(r, r.patterns().stream().map(Pattern::compile).toList()))
            .toList();

    // ── Configuration access (shared storage with SecurityConfigController) ──

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolGuardSection() {
        Map<String, Object> config = AgentStore.loadConfig();
        Object sec = config.get("security");
        if (sec instanceof Map<?, ?> m) {
            Object tg = m.get("tool_guard");
            if (tg instanceof Map<?, ?> tgm) {
                return new LinkedHashMap<>((Map<String, Object>) tgm);
            }
        }
        return new LinkedHashMap<>();
    }

    // ── Rule lookup for the API ──────────────────────────────────────

    /** Built-in rules as maps (for /config/security/tool-guard/builtin-rules). */
    public List<Map<String, Object>> builtinRules() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BuiltinRule r : BUILTIN_RULES) {
            result.add(r.toMap());
        }
        return result;
    }

    // ── Enforcement ──────────────────────────────────────────────────

    /**
     * Evaluate a tool call against the Tool Guard policy.
     *
     * @return a human-readable block reason, or {@code null} to allow
     */
    public String check(String toolName, Map<String, Object> input) {
        return check(toolName, input, toolGuardSection());
    }

    /**
     * Evaluate a tool call against an explicit Tool Guard policy map
     * (testable without touching agents.json).
     *
     * @return a human-readable block reason, or {@code null} to allow
     */
    public String check(String toolName, Map<String, Object> input,
                        Map<String, Object> cfg) {
        if (toolName == null) {
            return null;
        }
        if (!SkillService.bool(cfg.get("enabled"), true)) {
            return null;
        }

        // 1) Hard deny list
        Object denied = cfg.get("denied_tools");
        if (denied instanceof List<?> list) {
            for (Object d : list) {
                if (toolName.equals(String.valueOf(d))) {
                    log.warn("[tool-guard] denied tool call '{}'", toolName);
                    return "Tool Guard: tool '" + toolName + "' is denied by policy";
                }
            }
        }

        // 2) Guarded-tools scope: null means guard everything
        Object guarded = cfg.get("guarded_tools");
        if (guarded instanceof List<?> gList && !gList.isEmpty()) {
            boolean covered = false;
            for (Object g : gList) {
                if (toolName.equals(String.valueOf(g))) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return null;
            }
        }

        Map<String, Object> inputSafe = input == null ? Map.of() : input;

        // 3) Built-in rules (minus disabled_rules)
        List<String> disabled = toStringList(cfg.get("disabled_rules"));
        for (CompiledRule rule : COMPILED) {
            if (disabled.contains(rule.def().id())) {
                continue;
            }
            if (!rule.def().tools().contains(toolName)) {
                continue;
            }
            if (rule.matches(inputSafe)) {
                log.warn("[tool-guard] rule '{}' blocked tool '{}' input={}",
                        rule.def().id(), toolName, inputSafe);
                return "Tool Guard: " + rule.def().description()
                        + " (" + rule.def().id() + "). " + rule.def().remediation();
            }
        }

        // 4) Custom rules (user-defined, same schema as built-ins)
        Object custom = cfg.get("custom_rules");
        if (custom instanceof List<?> cList) {
            for (Object item : cList) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> rule = new LinkedHashMap<>((Map<String, Object>) m);
                List<String> tools = toStringList(rule.get("tools"));
                if (!tools.isEmpty() && !tools.contains(toolName)) {
                    continue;
                }
                List<String> params = toStringList(rule.get("params"));
                List<String> patterns = toStringList(rule.get("patterns"));
                if (params.isEmpty() || patterns.isEmpty()) {
                    continue;
                }
                for (String param : params) {
                    Object v = inputSafe.get(param);
                    if (v == null) {
                        continue;
                    }
                    String text = String.valueOf(v);
                    for (String pat : patterns) {
                        if (Pattern.compile(pat).matcher(text).find()) {
                            String id = SkillService.str(rule.get("id"), "custom");
                            String desc = SkillService.str(rule.get("description"), "custom rule match");
                            log.warn("[tool-guard] custom rule '{}' blocked tool '{}'", id, toolName);
                            return "Tool Guard: " + desc + " (rule " + id + ")";
                        }
                    }
                }
            }
        }

        // 5) Shell evasion heuristics for shell tools
        if (SHELL_TOOLS.contains(toolName)) {
            Object command = inputSafe.get("command");
            if (command instanceof String cmd && !cmd.isBlank()) {
                Object checks = cfg.get("shell_evasion_checks");
                Map<String, Object> checkMap = checks instanceof Map<?, ?> cm
                        ? new LinkedHashMap<>((Map<String, Object>) cm) : Map.of();
                for (ShellEvasionGuardian.Finding f :
                        ShellEvasionGuardian.scan(cmd, checkMap)) {
                    if (ShellEvasionGuardian.isBlocking(f)) {
                        log.warn("[tool-guard] shell evasion '{}' blocked command: {}",
                                f.category(), cmd);
                        return "Tool Guard: shell evasion detected (" + f.category()
                                + ") — " + f.message();
                    }
                    log.debug("[tool-guard] shell note '{}': {}", f.category(), f.message());
                }
            }
        }

        return null;
    }

    private static List<String> toStringList(Object v) {
        List<String> result = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
