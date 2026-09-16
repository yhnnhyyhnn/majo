package com.agent.coding.memory;

import com.agent.coding.agent.AgentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Executes {@code /memory} slash commands (ADR-0009), the majo counterpart
 * of QwenPaw's unified ReMe command surface (#7444) reduced to the minimal
 * useful set for the keyword backend: status/list/search/read/forget/write.
 *
 * <p>Returned text is user-facing markdown; when no memory backend resolves
 * the reply is an actionable fix hint (QwenPaw "Memory Manager Disabled"
 * semantics), never a silent failure.
 */
@Service
public class MemoryCommandService {

    private static final Logger log = LoggerFactory.getLogger(MemoryCommandService.class);
    private static final int MAX_READ_CHARS = 8000;
    private static final int DEFAULT_LIST_COUNT = 10;

    private final MemoryBackendRegistry registry;

    public MemoryCommandService(MemoryBackendRegistry registry) {
        this.registry = registry;
    }

    /** Execute a {@code /memory ...} command; the input includes the token. */
    public String execute(String agentId, String rawArgs) {
        String args = rawArgs == null ? "" : rawArgs.strip();
        String sub = args;
        String rest = "";
        int sp = args.indexOf(' ');
        if (sp >= 0) {
            sub = args.substring(0, sp).toLowerCase();
            rest = args.substring(sp + 1).strip();
        } else if (!args.isEmpty()) {
            sub = args.toLowerCase();
        }
        switch (sub) {
            case "", "status", "help" -> {
                return status(agentId);
            }
            case "list" -> {
                return list(agentId, rest);
            }
            case "search" -> {
                return search(agentId, rest);
            }
            case "read" -> {
                return read(agentId, rest);
            }
            case "forget" -> {
                return forget(agentId, rest);
            }
            case "write" -> {
                return write(agentId, rest);
            }
            default -> {
                return "未知子命令 \"" + sub + "\"。\n\n" + status(agentId);
            }
        }
    }

    private String status(String agentId) {
        MemoryBackend backend = resolveBackend(agentId);
        StringBuilder sb = new StringBuilder("**Memory 状态**\n");
        sb.append("- 配置后端: `").append(MemoryBackendRegistry.configuredBackendId(agentId)).append("`\n");
        sb.append("- 已注册后端: ").append(String.join(", ", registry.backendIds())).append("\n");
        if (backend == null) {
            sb.append("- 可用性: ❌ 无可用后端 — 请检查 `memory_manager_backend` 配置")
              .append("（当前写入与检索已暂停）\n");
            return sb.toString();
        }
        sb.append("- 生效后端: `").append(backend.id()).append("`\n");
        sb.append("- 可用性: ✅\n");
        sb.append("- 自动写入: ")
          .append(autoMemoryEnabled(agentId) ? "已启用" : "未启用（reme_light_memory_config.auto_memory_config.enabled）")
          .append("\n");
        sb.append("\n子命令: `status` / `list [n]` / `search <关键词>` / `read <路径>` / `forget <路径>` / `write <内容>`");
        return sb.toString();
    }

    private String list(String agentId, String rest) {
        int limit = DEFAULT_LIST_COUNT;
        try {
            if (!rest.isBlank()) {
                limit = Math.max(1, Integer.parseInt(rest.strip()));
            }
        } catch (NumberFormatException ignored) {
        }
        List<Path> files = new ArrayList<>();
        Path workspace = workspaceOf(agentId);
        if (workspace == null) {
            return "工作区不可用";
        }
        Path memoryDir = workspace.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            try (Stream<Path> stream = Files.walk(memoryDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".md"))
                        .filter(f -> !f.getFileName().toString().startsWith("."))
                        .forEach(files::add);
            } catch (IOException e) {
                return "读取记忆目录失败: " + e.getMessage();
            }
        }
        if (files.isEmpty()) {
            return "记忆目录为空（可用 `/memory write <内容>` 写入第一条记忆）。";
        }
        files.sort((a, b) -> compareLastModifiedDesc(b, a));
        StringBuilder sb = new StringBuilder("共 ").append(files.size()).append(" 个记忆文件，最近 ")
                .append(Math.min(limit, files.size())).append(" 个:\n");
        for (int i = 0; i < Math.min(limit, files.size()); i++) {
            Path f = files.get(i);
            sb.append("- `").append(workspace.relativize(f).toString().replace('\\', '/'))
              .append("`（").append(safeSize(f)).append(" 字节）\n");
        }
        return sb.toString();
    }

    private String search(String agentId, String query) {
        if (query.isBlank()) {
            return "用法: `/memory search <关键词>`";
        }
        MemoryBackend backend = resolveBackend(agentId);
        if (backend == null) {
            return backendUnavailable();
        }
        List<MemoryHit> hits = backend.search(query, 5);
        if (hits.isEmpty()) {
            return "未找到与 \"" + query + "\" 相关的记忆。";
        }
        StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 条相关记忆:\n");
        for (MemoryHit hit : hits) {
            sb.append("- `").append(hit.source()).append("`");
            if (!hit.snippet().isBlank()) {
                String snippet = hit.snippet().length() > 200
                        ? hit.snippet().substring(0, 200) + "…" : hit.snippet();
                sb.append(": ").append(snippet);
            }
            sb.append("\n");
        }
        sb.append("可用 `/memory read <路径>` 查看完整内容。");
        return sb.toString();
    }

    private String read(String agentId, String relPath) {
        Path file = resolveMemoryPath(agentId, relPath);
        if (file == null) {
            return "用法: `/memory read <路径>`（路径需位于 memory/ 目录下，先用 `/memory list` 查看）";
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > MAX_READ_CHARS) {
                content = content.substring(0, MAX_READ_CHARS) + "\n…[内容过长已截断]";
            }
            return "**" + relPath + "**\n\n" + content;
        } catch (IOException e) {
            return "读取失败: " + e.getMessage();
        }
    }

    private String forget(String agentId, String relPath) {
        Path file = resolveMemoryPath(agentId, relPath);
        if (file == null) {
            return "用法: `/memory forget <路径>`（路径需位于 memory/ 目录下）";
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            return "删除失败: " + e.getMessage();
        }
        reindexQuietly(agentId);
        return "已删除 `" + relPath + "` 并重建索引。";
    }

    private String write(String agentId, String content) {
        if (content.isBlank()) {
            return "用法: `/memory write <要记住的内容>`";
        }
        MemoryBackend backend = resolveBackend(agentId);
        if (backend == null) {
            return backendUnavailable();
        }
        try {
            backend.remember(content, Map.of("trigger", "manual", "source", "/memory"));
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
        return "已写入长期记忆（后端 `" + backend.id() + "`）。";
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String backendUnavailable() {
        return "**Memory 后端不可用** — 请检查 `memory_manager_backend` 配置"
                + "（设置后重启生效），当前写入与检索已暂停。";
    }

    private static boolean autoMemoryEnabled(String agentId) {
        try {
            Map<String, Object> running = AgentStore.getRunningConfig(agentId);
            Object reme = running.get("reme_light_memory_config");
            if (reme instanceof Map<?, ?> m) {
                Object auto = m.get("auto_memory_config");
                return auto instanceof Map<?, ?> a
                        && Boolean.TRUE.equals(com.agent.coding.skill.SkillService.bool(a.get("enabled"), false));
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Resolve the active backend for an agent; protected for test stubbing. */
    protected MemoryBackend resolveBackend(String agentId) {
        return registry.resolve(agentId);
    }

    /** Resolve the agent workspace; protected for test stubbing. */
    protected Path workspaceOf(String agentId) {
        try {
            return AgentStore.workspaceDirForAgent(agentId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve a user-supplied path against the workspace memory/ directory,
     * rejecting traversal outside it (symlink-aware). Accepts both
     * memory-relative ("daily/x.md") and workspace-relative
     * ("memory/daily/x.md") spellings — list/search surface the latter.
     */
    private Path resolveMemoryPath(String agentId, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return null;
        }
        Path workspace = workspaceOf(agentId);
        if (workspace == null) {
            return null;
        }
        Path memoryDir = workspace.resolve("memory").normalize();
        String cleaned = relPath.strip().replace('\\', '/');
        if (cleaned.startsWith("memory/")) {
            cleaned = cleaned.substring("memory/".length());
        }
        Path target = memoryDir.resolve(cleaned).normalize();
        if (!target.startsWith(memoryDir) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            if (!target.toRealPath().startsWith(memoryDir.toRealPath())) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return target;
    }

    private void reindexQuietly(String agentId) {
        MemoryBackend backend = resolveBackend(agentId);
        Path workspace = workspaceOf(agentId);
        if (backend instanceof KeywordMemoryBackend keyword && workspace != null) {
            try {
                keyword.rebuildForPath(workspace);
            } catch (Exception e) {
                // Warn, not debug: a failed reindex leaves search serving the
                // stale persisted index (a CI gate failure traced back here).
                log.warn("[memory-cmd] reindex failed for {}: {}", agentId, e.toString());
            }
        }
    }

    private static int compareLastModifiedDesc(Path a, Path b) {
        try {
            return Long.compare(Files.getLastModifiedTime(a).toMillis(),
                    Files.getLastModifiedTime(b).toMillis());
        } catch (IOException e) {
            return 0;
        }
    }

    private static long safeSize(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return 0;
        }
    }
}
