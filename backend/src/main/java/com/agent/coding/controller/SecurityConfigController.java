package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global security configuration endpoints, ported from qwenpaw
 * app/routers/config.py (security section). Persisted in the agents.json root
 * under the {@code security} key. The frontend contract (security.ts) wins
 * over qwenpaw field names where they differ (e.g. file-guard {@code paths}
 * vs qwenpaw {@code sensitive_files}).
 */
@RestController
@RequestMapping("/api/config/security")
@CrossOrigin(origins = "*")
public class SecurityConfigController {

    // ── storage helpers ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> securitySection() {
        Map<String, Object> config = AgentStore.loadConfig();
        Object sec = config.get("security");
        if (sec instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> subsection(Map<String, Object> sec, String key) {
        Object v = sec.get(key);
        if (v instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    private static void persistSecurity(Map<String, Object> sec) {
        AgentStore.updateRoot("security", sec);
    }

    // ── Tool Guard ──────────────────────────────────────────────────

    private static Map<String, Object> toolGuardDefaults() {
        Map<String, Object> tg = new LinkedHashMap<>();
        tg.put("enabled", true);
        tg.put("guarded_tools", null);
        tg.put("denied_tools", new ArrayList<>());
        tg.put("auto_denied_rules", List.of("SAFETY_CHECKS_DESTRUCTIVE_COMMAND"));
        tg.put("custom_rules", new ArrayList<>());
        tg.put("disabled_rules", new ArrayList<>());
        Map<String, Object> evasion = new LinkedHashMap<>();
        evasion.put("command_substitution", true);
        evasion.put("environment_variable", true);
        evasion.put("input_redirection", true);
        evasion.put("null_byte", true);
        evasion.put("quoting", true);
        evasion.put("semantic_injection", true);
        evasion.put("shell_metacharacters", true);
        evasion.put("whitespace_variants", true);
        tg.put("shell_evasion_checks", evasion);
        return tg;
    }

    @GetMapping("/tool-guard")
    public Map<String, Object> getToolGuard() {
        Map<String, Object> tg = toolGuardDefaults();
        tg.putAll(subsection(securitySection(), "tool_guard"));
        return tg;
    }

    @PutMapping("/tool-guard")
    public Map<String, Object> updateToolGuard(@RequestBody Map<String, Object> body) {
        Map<String, Object> sec = securitySection();
        sec.put("tool_guard", body);
        persistSecurity(sec);
        return body;
    }

    @GetMapping("/tool-guard/builtin-rules")
    public List<Map<String, Object>> builtinRules() {
        return new ArrayList<>();
    }

    // ── Sandbox ─────────────────────────────────────────────────────

    @GetMapping("/sandbox")
    public Map<String, Object> getSandbox(@RequestParam(required = false) Boolean enabled) {
        Map<String, Object> sec = securitySection();
        boolean configured = SkillService.bool(sec.get("sandbox_enabled"), false);
        boolean effective = configured;
        String reason = null;
        if (enabled != null) {
            configured = enabled;
            effective = enabled;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", configured);
        result.put("effective", effective);
        result.put("reason", reason);
        return result;
    }

    @PutMapping("/sandbox")
    public Map<String, Object> updateSandbox(@RequestBody Map<String, Object> body) {
        boolean enabled = SkillService.bool(body.get("enabled"), false);
        Map<String, Object> sec = securitySection();
        sec.put("sandbox_enabled", enabled);
        persistSecurity(sec);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("effective", enabled);
        result.put("reason", null);
        return result;
    }

    // ── File Guard ──────────────────────────────────────────────────

    private static Map<String, Object> fileGuardDefaults() {
        Map<String, Object> fg = new LinkedHashMap<>();
        fg.put("enabled", true);
        fg.put("paths", new ArrayList<>());
        fg.put("allow_preview_outside_workspace", true);
        return fg;
    }

    @GetMapping("/file-guard")
    public Map<String, Object> getFileGuard() {
        Map<String, Object> fg = fileGuardDefaults();
        fg.putAll(subsection(securitySection(), "file_guard"));
        return fg;
    }

    @PutMapping("/file-guard")
    public Map<String, Object> updateFileGuard(@RequestBody Map<String, Object> body) {
        Map<String, Object> sec = securitySection();
        sec.put("file_guard", body);
        persistSecurity(sec);
        return body;
    }

    // ── Skill Scanner ───────────────────────────────────────────────

    private static Map<String, Object> skillScannerDefaults() {
        Map<String, Object> ss = new LinkedHashMap<>();
        ss.put("mode", "warn");
        ss.put("timeout", 30);
        ss.put("whitelist", new ArrayList<>());
        return ss;
    }

    @GetMapping("/skill-scanner")
    public Map<String, Object> getSkillScanner() {
        Map<String, Object> ss = skillScannerDefaults();
        ss.putAll(subsection(securitySection(), "skill_scanner"));
        return ss;
    }

    @PutMapping("/skill-scanner")
    public Map<String, Object> updateSkillScanner(@RequestBody Map<String, Object> body) {
        Map<String, Object> sec = securitySection();
        sec.put("skill_scanner", body);
        persistSecurity(sec);
        return body;
    }

    @GetMapping("/skill-scanner/blocked-history")
    public List<Map<String, Object>> blockedHistory() {
        Map<String, Object> sec = securitySection();
        Object v = sec.get("skill_scanner_blocked_history");
        if (v instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    @DeleteMapping("/skill-scanner/blocked-history")
    public Map<String, Object> clearBlockedHistory() {
        Map<String, Object> sec = securitySection();
        sec.remove("skill_scanner_blocked_history");
        persistSecurity(sec);
        return Map.of("cleared", true);
    }

    @DeleteMapping("/skill-scanner/blocked-history/{index}")
    public Map<String, Object> removeBlockedEntry(@PathVariable int index) {
        Map<String, Object> sec = securitySection();
        Object v = sec.get("skill_scanner_blocked_history");
        if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            if (index >= 0 && index < copy.size()) {
                copy.remove(index);
                sec.put("skill_scanner_blocked_history", copy);
                persistSecurity(sec);
                return Map.of("removed", true);
            }
        }
        return Map.of("removed", false);
    }

    @PostMapping("/skill-scanner/whitelist")
    public Map<String, Object> addWhitelist(@RequestBody Map<String, Object> body) {
        String skillName = SkillService.str(body.get("skill_name"));
        String contentHash = SkillService.str(body.get("content_hash"), "");
        Map<String, Object> sec = securitySection();
        Map<String, Object> ss = subsection(sec, "skill_scanner");
        List<Object> whitelist = new ArrayList<>();
        Object w = ss.get("whitelist");
        if (w instanceof List<?> list) {
            whitelist.addAll(list);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("skill_name", skillName);
        entry.put("content_hash", contentHash);
        entry.put("added_at", Instant.now().toString());
        whitelist.add(entry);
        ss.put("whitelist", whitelist);
        sec.put("skill_scanner", ss);
        persistSecurity(sec);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("whitelisted", true);
        result.put("skill_name", skillName);
        return result;
    }

    @DeleteMapping("/skill-scanner/whitelist/{skill_name}")
    public Map<String, Object> removeWhitelist(@PathVariable String skill_name) {
        Map<String, Object> sec = securitySection();
        Map<String, Object> ss = subsection(sec, "skill_scanner");
        List<Object> whitelist = new ArrayList<>();
        Object w = ss.get("whitelist");
        if (w instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m
                        && skill_name.equals(SkillService.str(m.get("skill_name")))) {
                    continue;
                }
                whitelist.add(item);
            }
        }
        ss.put("whitelist", whitelist);
        sec.put("skill_scanner", ss);
        persistSecurity(sec);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", true);
        result.put("skill_name", skill_name);
        return result;
    }

    // ── Allow No Auth Hosts ─────────────────────────────────────────

    @GetMapping("/allow-no-auth-hosts")
    public Map<String, Object> getAllowNoAuthHosts() {
        Map<String, Object> sec = securitySection();
        Object hosts = sec.get("allow_no_auth_hosts");
        List<String> list = new ArrayList<>();
        if (hosts instanceof List<?> l) {
            for (Object h : l) {
                list.add(SkillService.str(h));
            }
        } else {
            list.addAll(List.of("127.0.0.1", "::1"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hosts", list);
        return result;
    }

    @PutMapping("/allow-no-auth-hosts")
    public Map<String, Object> updateAllowNoAuthHosts(@RequestBody Map<String, Object> body) {
        List<String> hosts = new ArrayList<>();
        Object h = body.get("hosts");
        if (h instanceof List<?> list) {
            for (Object item : list) {
                hosts.add(SkillService.str(item));
            }
        }
        Map<String, Object> sec = securitySection();
        sec.put("allow_no_auth_hosts", hosts);
        persistSecurity(sec);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hosts", hosts);
        return result;
    }
}
