package com.agent.coding.tool;

import com.agent.coding.WorkspaceContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class GitTools {

    private org.eclipse.jgit.lib.Repository repo() throws IOException {
        return new FileRepositoryBuilder().findGitDir(WorkspaceContext.get().toFile()).build();
    }

    @Tool(name = "git_status", description = "查看 Git 仓库状态")
    public String status() throws Exception {
        try (var r = repo(); var g = new Git(r)) {
            var s = g.status().call();
            var sb = new StringBuilder("分支: " + r.getBranch() + "\n");
            if (!s.getModified().isEmpty()) sb.append("已修改: ").append(s.getModified()).append("\n");
            if (!s.getAdded().isEmpty()) sb.append("已暂存: ").append(s.getAdded()).append("\n");
            if (!s.getUntracked().isEmpty()) sb.append("未跟踪: ").append(s.getUntracked()).append("\n");
            if (s.isClean()) sb.append("(干净)");
            return sb.toString();
        }
    }

    @Tool(name = "git_diff", description = "查看工作区变更")
    public String diff(@ToolParam(name = "staged", description = "只看已暂存(可选)") Boolean staged) throws Exception {
        try (var r = repo(); var g = new Git(r)) {
            var out = new ByteArrayOutputStream();
            if (staged != null && staged) g.diff().setCached(true).setOutputStream(out).call();
            else g.diff().setOutputStream(out).call();
            String d = out.toString();
            return d.isEmpty() ? "(无变更)" : d;
        }
    }

    @Tool(name = "git_branch", description = "创建或列出分支")
    public String branch(@ToolParam(name = "name", description = "分支名(可选)") String name) throws Exception {
        try (var r = repo(); var g = new Git(r)) {
            if (name != null && !name.isBlank()) {
                g.checkout().setCreateBranch(true).setName(name).call();
                return "创建并切换到: " + name;
            }
            var sb = new StringBuilder();
            var cur = r.getBranch();
            g.branchList().call().forEach(ref -> {
                String b = ref.getName().replace("refs/heads/", "");
                sb.append(b.equals(cur) ? "* " : "  ").append(b).append("\n");
            });
            return sb.toString();
        }
    }

    @Tool(name = "git_commit", description = "提交暂存的变更")
    public String commit(@ToolParam(name = "message", description = "提交信息") String msg) throws Exception {
        try (var r = repo(); var g = new Git(r)) {
            var rev = g.commit().setMessage(msg).call();
            return "提交: " + rev.getName();
        }
    }

    @Tool(name = "git_add", description = "暂存文件")
    public String add(@ToolParam(name = "filePattern", description = "文件模式") String pattern) throws Exception {
        try (var g = new Git(repo())) { g.add().addFilepattern(pattern).call(); }
        return "已暂存: " + pattern;
    }

    @Tool(name = "git_log", description = "查看提交历史")
    public String log(@ToolParam(name = "count", description = "条数(可选,默认5)") Integer count) throws Exception {
        int n = count != null ? Math.min(count, 20) : 5;
        try (var r = repo(); var g = new Git(r)) {
            var sb = new StringBuilder();
            g.log().setMaxCount(n).call().forEach(rev ->
                sb.append(String.format("%s %s%n    %s%n", rev.getName().substring(0, 7),
                    rev.getAuthorIdent().getName(), rev.getFullMessage().trim())));
            return sb.toString();
        }
    }
}
