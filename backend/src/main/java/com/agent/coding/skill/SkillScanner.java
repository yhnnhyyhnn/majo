package com.agent.coding.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Skill security scanner.
 *
 * Ports the shipped signature rules of qwenpaw/security/skill_scanner to Java
 * regexes. Produces ScanResult with severity/findings matching the Python model
 * and raises {@link SkillScanError} when CRITICAL/HIGH findings are found.
 */
public class SkillScanner {

    private static final Logger log = LoggerFactory.getLogger(SkillScanner.class);

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW, INFO, SAFE
    }

    /** One rule definition (ported from rules/signatures/*.yaml). */
    public static class Rule {
        public final String ruleId;
        public final String category;
        public final Severity severity;
        public final String title;
        public final String description;
        public final List<Pattern> patterns;
        public final List<String> fileGlobs;

        public Rule(String ruleId, String category, Severity severity, String title,
                    String description, List<Pattern> patterns, List<String> fileGlobs) {
            this.ruleId = ruleId;
            this.category = category;
            this.severity = severity;
            this.title = title;
            this.description = description;
            this.patterns = patterns;
            this.fileGlobs = fileGlobs;
        }
    }

    /** Scan finding (mirrors Python Finding.to_dict). */
    public static class Finding {
        public String id;
        public String ruleId;
        public String category;
        public String severity;
        public String title;
        public String description;
        public String filePath;
        public Integer lineNumber;
        public String snippet;
        public String remediation;
        public String analyzer;
        public Map<String, Object> metadata = new LinkedHashMap<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("rule_id", ruleId);
            m.put("category", category);
            m.put("severity", severity);
            m.put("title", title);
            m.put("description", description);
            m.put("file_path", filePath);
            m.put("line_number", lineNumber);
            m.put("snippet", snippet);
            m.put("remediation", remediation);
            m.put("analyzer", analyzer);
            m.put("metadata", metadata);
            return m;
        }
    }

    /** Scan result (mirrors Python ScanResult.to_dict). */
    public static class ScanResult {
        public String skillName;
        public String skillPath;
        public List<Finding> findings = new ArrayList<>();
        public double scanDurationSeconds;
        public List<String> analyzersUsed = new ArrayList<>();
        public String timestamp;

        public boolean isSafe() {
            return findings.stream().noneMatch(f ->
                    f.severity.equals(Severity.CRITICAL.name()) || f.severity.equals(Severity.HIGH.name()));
        }

        public String maxSeverity() {
            if (findings.isEmpty()) return Severity.SAFE.name();
            for (Severity sev : List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO)) {
                if (findings.stream().anyMatch(f -> f.severity.equals(sev.name()))) return sev.name();
            }
            return Severity.SAFE.name();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skill_name", skillName);
            m.put("skill_path", skillPath);
            m.put("is_safe", isSafe());
            m.put("max_severity", maxSeverity());
            m.put("findings_count", findings.size());
            List<Map<String, Object>> findingMaps = new ArrayList<>();
            for (Finding f : findings) findingMaps.add(f.toMap());
            m.put("findings", findingMaps);
            m.put("scan_duration_seconds", scanDurationSeconds);
            m.put("analyzers_used", analyzersUsed);
            m.put("timestamp", timestamp);
            return m;
        }
    }

    // ------------------------------------------------------------------
    // Rules (ported from security/skill_scanner/rules/signatures/*.yaml)
    // ------------------------------------------------------------------

    private static final List<Rule> RULES = buildRules();

    private static Rule rule(String id, String category, Severity sev, String title,
                             String description, String[] globs, String... patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String p : patterns) {
            try {
                compiled.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            } catch (Exception e) {
                log.warn("Bad rule pattern for {}: {}", id, p);
            }
        }
        return new Rule(id, category, sev, title, description, compiled, Arrays.asList(globs));
    }

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();
        rules.add(rule("SCAN-001", "command_injection", Severity.HIGH,
                "Command injection pattern",
                "Detected shell command execution with user-controlled input.",
                new String[]{"*.py", "*.sh", "*.bash", "*.zsh", "*.js", "*.ts"},
                "os\\.system\\s*\\(", "subprocess\\.(call|run|Popen)\\s*\\(",
                "eval\\s*\\(\\s*(input|request|body)", "exec\\s*\\(\\s*(input|request|body)"));
        rules.add(rule("SCAN-002", "data_exfiltration", Severity.HIGH,
                "Data exfiltration pattern",
                "Detected network upload of local files or secrets to external hosts.",
                new String[]{"*.py", "*.sh", "*.js", "*.ts"},
                "(urllib|requests|httpx|axios|fetch)\\s*\\([^)]*(https?://[^)]*\\b(pastebin|webhook|ngrok|transfer\\.sh))",
                "curl\\s+[^|&;]*\\|\\s*(nc|netcat)"));
        rules.add(rule("SCAN-003", "hardcoded_secrets", Severity.HIGH,
                "Hardcoded secret pattern",
                "Detected hardcoded API keys or passwords.",
                new String[]{"*.py", "*.sh", "*.js", "*.ts", "*.yaml", "*.yml", "*.json"},
                "(sk-[A-Za-z0-9]{20,})", "((api|secret|token|password|passwd)[_-]?\\s*[:=]\\s*['\"][A-Za-z0-9!@#$%^&*_]{12,}['\"])",
                "(AKIA[0-9A-Z]{16})"));
        rules.add(rule("SCAN-004", "obfuscation", Severity.MEDIUM,
                "Obfuscation pattern",
                "Detected obfuscated or encoded payload execution.",
                new String[]{"*.py", "*.sh", "*.js", "*.ts"},
                "base64\\.b64decode\\s*\\([^)]*\\).*exec|eval\\s*\\(.*base64",
                "\\b(decode|unescape)\\s*\\(\\s*['\"][A-Za-z0-9+/=]{200,}['\"]"));
        rules.add(rule("SCAN-005", "prompt_injection", Severity.MEDIUM,
                "Prompt injection pattern",
                "Detected instruction-override or system-prompt manipulation.",
                new String[]{"*.md", "*.txt", "*.py", "*.js", "*.ts"},
                "ignore\\s+(all\\s+)?(previous|prior|above|earlier).{0,40}instructions",
                "you\\s+are\\s+now\\s+(?!a\\s+(helpful|skilled|coding))\\w+",
                "disregard.{0,40}(instructions|system\\s+prompt)"));
        rules.add(rule("SCAN-006", "social_engineering", Severity.MEDIUM,
                "Social engineering pattern",
                "Detected credential-harvesting or phishing language.",
                new String[]{"*.md", "*.txt"},
                "ask\\s+(the\\s+)?user.{0,30}(password|credential|secret|api\\s*key)",
                "redirect.{0,50}(login|credentials)"));
        rules.add(rule("SCAN-007", "supply_chain", Severity.MEDIUM,
                "Supply-chain attack pattern",
                "Detected installation of untrusted packages or remote script execution.",
                new String[]{"*.sh", "*.bash", "*.zsh", "*.py"},
                "curl[^|&;]*\\|\\s*(sudo\\s+)?(bash|sh)", "wget[^|&;]*\\|\\s*(sudo\\s+)?(bash|sh)",
                "pip\\s+install[^\\r\\n]*(github\\.com|git\\+)"));
        rules.add(rule("SCAN-008", "unauthorized_tool_use", Severity.LOW,
                "Unauthorized tool use pattern",
                "Detected references to tools outside the allowed toolset.",
                new String[]{"*.md"},
                "\\b(rm\\s+-rf\\s+/|shutdown\\s+now|format\\s+c:)\\b"));
        return rules;
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private static final Map<String, CachedResult> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> WHITELISTED_SKILLS = ConcurrentHashMap.newKeySet();

    private record CachedResult(long mtimeNanos, ScanResult result) {}

    public static void whitelistSkill(String skillName) {
        if (skillName != null && !skillName.isEmpty()) WHITELISTED_SKILLS.add(skillName);
    }

    public static boolean isSkillWhitelisted(String skillName) {
        return WHITELISTED_SKILLS.contains(skillName);
    }

    /** Scan a skill directory, returning null when safe or a result map with findings. */
    public static ScanResult scanSkillDirectory(Path skillDir, String skillName) {
        long start = System.nanoTime();
        ScanResult result = new ScanResult();
        result.skillName = skillName != null && !skillName.isEmpty() ? skillName : skillDir.getFileName().toString();
        result.skillPath = skillDir.toString();
        result.timestamp = java.time.Instant.now().toString();
        result.analyzersUsed.add("pattern_analyzer");

        try {
            long dirMtime = Files.getLastModifiedTime(skillDir).toMillis();
            if (skillName != null && !skillName.isEmpty()) {
                CachedResult cached = CACHE.get(skillDir.toString());
                if (cached != null && cached.mtimeNanos == dirMtime) {
                    result = cached.result();
                    result.scanDurationSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    return result;
                }
            }
        } catch (IOException ignored) {}

        if (!Files.isDirectory(skillDir)) {
            result.scanDurationSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
            return result;
        }

        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(skillDir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            log.warn("Failed to walk skill dir {}: {}", skillDir, e.getMessage());
        }
        // skip the top-level SKILL.md itself for rule scanning
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.equals("SKILL.md") && file.getParent().equals(skillDir)) continue;
            String rel = skillDir.relativize(file).toString().replace("\\", "/");
            String lower = rel.toLowerCase();
            for (Rule rule : RULES) {
                boolean globMatches = rule.fileGlobs.isEmpty();
                for (String glob : rule.fileGlobs) {
                    if (lower.endsWith(glob.substring(1))) { globMatches = true; break; }
                }
                if (!globMatches) continue;
                String content;
                try {
                    content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    continue;
                }
                for (Pattern p : rule.patterns) {
                    var m = p.matcher(content);
                    while (m.find()) {
                        Finding f = new Finding();
                        f.id = rule.ruleId + "-" + UUID.randomUUID().toString().substring(0, 8);
                        f.ruleId = rule.ruleId;
                        f.category = rule.category;
                        f.severity = rule.severity.name();
                        f.title = rule.title;
                        f.description = rule.description;
                        f.filePath = rel;
                        f.lineNumber = lineNumberAt(content, m.start());
                        f.snippet = content.substring(Math.max(0, m.start() - 40),
                                Math.min(content.length(), m.end() + 40));
                        f.remediation = "Review and remove the flagged pattern.";
                        f.analyzer = "pattern_analyzer";
                        result.findings.add(f);
                    }
                }
            }
        }
        result.scanDurationSeconds = (System.nanoTime() - start) / 1_000_000_000.0;

        if (skillName != null && !skillName.isEmpty()) {
            try {
                long mtime = Files.getLastModifiedTime(skillDir).toMillis();
                CACHE.put(skillDir.toString(), new CachedResult(mtime, result));
            } catch (IOException ignored) {}
        }
        return result;
    }

    private static int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** Scan and raise {@link SkillScanError} with 422-style payload when blocking findings exist. */
    public static void scanSkillDirOrRaise(Path skillDir, String skillName) {
        if (skillName != null && WHITELISTED_SKILLS.contains(skillName)) return;
        ScanResult result = scanSkillDirectory(skillDir, skillName);
        if (!result.isSafe()) {
            List<Map<String, Object>> findings = new ArrayList<>();
            for (Finding f : result.findings) {
                if (f.severity.equals(Severity.CRITICAL.name()) || f.severity.equals(Severity.HIGH.name())) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("severity", f.severity);
                    fm.put("title", f.title);
                    fm.put("description", f.description);
                    fm.put("file_path", f.filePath);
                    fm.put("line_number", f.lineNumber);
                    fm.put("rule_id", f.ruleId);
                    findings.add(fm);
                }
            }
            throw new SkillScanError(
                    "Skill scan failed: " + result.skillName + " has " + result.maxSeverity() + " findings",
                    result.skillName, findings);
        }
    }
}
