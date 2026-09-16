package com.agent.coding.agent;

import com.agent.coding.skill.SkillService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Coding Mode switch (ADR-0010) — runtime side of the existing
 * {@code coding_mode} agent-profile flag that the sidebar
 * CodingModeToggle and {@code /api/coding-mode} already persist. When
 * enabled, the coding discipline prompt is injected at PRE_CALL and the
 * lsp/ast_search tools accept calls; when disabled both degrade to a
 * readable "disabled" reply (QwenPaw instead filters the tools out of the
 * registry — majo's shared Toolkit gates at call time, same runtime
 * effect). Defaults to off, matching the toggle's persisted default.
 */
@Service
public class CodingModeService {

    public boolean isEnabled(String agentId) {
        try {
            Map<String, Object> profile = AgentStore.getProfile(agentId);
            if (profile == null) {
                return false;
            }
            Map<String, Object> coding = SkillService.asMap(profile.get("coding_mode"));
            return SkillService.bool(coding.get("enabled"), false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Readable gate reply for tools invoked while coding mode is off. */
    public static String disabledReply() {
        return "Coding Mode 未启用：请打开侧栏的 Coding Mode 开关后重试（即时生效）。";
    }
}
