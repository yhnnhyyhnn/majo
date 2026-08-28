package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.skill.SkillStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Copy a workspace file into the shared uploads directory so the user can
 * preview/download it via {@code /api/files/preview/{name}}. Mirrors
 * QwenPaw's send_file_to_user (media outbox).
 */
@Component
public class SendFileToUserTool {

    private static Path uploadsDir() {
        return SkillStore.WORKING_DIR.resolve("uploads");
    }

    @Tool(name = "send_file_to_user", description = "把工作区文件发送给用户（拷入上传目录供预览/下载）")
    public String sendFileToUser(
        @ToolParam(name = "path", description = "工作区内的文件路径") String path
    ) {
        if (path == null || path.isBlank()) {
            return "错误: path 不能为空";
        }
        try {
            Path source = WorkspaceContext.get().resolve(path).normalize();
            if (!Files.isRegularFile(source)) {
                return "错误: 文件不存在: " + path;
            }
            long size = Files.size(source);
            if (size > 100 * 1024 * 1024) {
                return "错误: 文件过大 (" + size + " bytes)，发送上限 100MB";
            }
            String name = Path.of(path).getFileName().toString();
            String savedName = UUID.randomUUID().toString().substring(0, 8) + "-" + name;
            Path dir = uploadsDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(savedName);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            return "文件已发送给用户: " + name + " (" + size + " bytes)\n"
                    + "预览地址: /api/files/preview/" + savedName;
        } catch (Exception e) {
            return "发送失败: " + e.getMessage();
        }
    }
}
