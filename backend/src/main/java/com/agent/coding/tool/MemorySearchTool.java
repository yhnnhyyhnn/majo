package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import com.agent.coding.memory.MemoryHit;
import com.agent.coding.memory.MemoryBackendRegistry;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Search the agent's long-term memory through the configured memory backend
 * (ADR-0008). Read-only; returns source paths with snippets.
 */
@Component
public class MemorySearchTool {

    private final MemoryBackendRegistry registry;

    public MemorySearchTool(MemoryBackendRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "memory_search",
            description = "搜索该 Agent 的长期记忆（memory 目录），返回相关笔记路径与摘要")
    public String memorySearch(
        @ToolParam(name = "query", description = "检索关键词") String query
    ) {
        if (query == null || query.isBlank()) {
            return "错误: query 不能为空";
        }
        try {
            // The workspace path ends with workspaces/{agent_id} by convention.
            Path ws = WorkspaceContext.get();
            String agentId = ws.getFileName() != null ? ws.getFileName().toString() : "default";
            var backend = registry.resolve(agentId);
            if (backend == null) {
                return "记忆后端不可用";
            }
            List<MemoryHit> hits = backend.search(query, 5);
            if (hits.isEmpty()) {
                return "未找到与 \"" + query + "\" 相关的记忆。";
            }
            StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 条相关记忆:\n");
            for (MemoryHit hit : hits) {
                sb.append("- ").append(hit.source());
                if (!hit.snippet().isBlank()) {
                    String snippet = hit.snippet().length() > 200
                            ? hit.snippet().substring(0, 200) + "..." : hit.snippet();
                    sb.append(": ").append(snippet);
                }
                sb.append("\n");
            }
            sb.append("可用 read_file 查看完整内容。");
            return sb.toString();
        } catch (Exception e) {
            return "记忆检索失败: " + e.getMessage();
        }
    }
}
