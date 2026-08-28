package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.skill.SkillService;
import com.agent.coding.skill.SkillStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Materialize a skill definition (SKILL.md with YAML frontmatter) into the
 * current workspace's {@code skills/} directory so the harness can load it
 * as a skill. Mirrors QwenPaw's materialize_skill.
 */
@Component
public class MaterializeSkillTool {

    @Tool(name = "materialize_skill", description = "把技能定义(SKILL.md)写入当前工作区的 skills 目录")
    public String materializeSkill(
        @ToolParam(name = "skill_content", description = "SKILL.md 内容(YAML frontmatter + 正文)") String skillContent
    ) {
        if (skillContent == null || skillContent.isBlank()) {
            return "错误: skill_content 不能为空";
        }
        try {
            SkillService.validateSkillContent(skillContent);
            Map<String, Object> fm = SkillStore.parseFrontmatter(skillContent);
            String name = fm.get("name") == null ? "" : String.valueOf(fm.get("name")).trim();
            if (name.isBlank() || !name.matches("^[A-Za-z0-9_-]+$")) {
                return "错误: frontmatter 缺少合法的 name 字段(仅允许字母数字_-)";
            }
            Path ws = WorkspaceContext.get();
            Path skillDir = ws.resolve("skills").resolve(name);
            Files.createDirectories(skillDir);
            Path target = skillDir.resolve("SKILL.md");
            Files.writeString(target, skillContent);
            return "技能已物化到工作区: " + target + " (" + skillContent.length() + " bytes)\n"
                    + "技能名: " + name + " — 可在后续任务中按需加载。";
        } catch (com.agent.coding.skill.SkillsError e) {
            return "错误: " + e.getMessage();
        } catch (Exception e) {
            return "物化失败: " + e.getMessage();
        }
    }
}
