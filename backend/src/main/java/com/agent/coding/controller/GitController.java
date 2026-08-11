package com.agent.coding.controller;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.service.GitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Git workspace endpoints, ported from qwenpaw app/routers/git.py.
 * Operates on the agent's coding directory (workspace dir) resolved from
 * {@code X-Agent-Id}. Auto-initialises a repo on first status call.
 */
@RestController
@RequestMapping("/api/workspace/git")
@CrossOrigin(origins = "*")
public class GitController {

    private final GitService gitService;

    public GitController(GitService gitService) {
        this.gitService = gitService;
    }

    private Path resolveDir(HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        if (agentId == null || agentId.isBlank()) {
            agentId = request.getParameter("agent");
        }
        if (agentId == null || agentId.isBlank()) {
            agentId = "default";
        }
        return AgentStore.workspaceDirForAgent(agentId);
    }

    private ResponseEntity<Object> handle(Exception e) {
        String msg = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        if (msg.toLowerCase().contains("not a git repository")
                || msg.toLowerCase().contains("no git directory")) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("detail", "Not a git repository"));
        }
        return ResponseEntity.badRequest().body(Map.of("detail", msg));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.status(resolveDir(request)));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @GetMapping("/branches")
    public ResponseEntity<?> branches(HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.branches(resolveDir(request)));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        try {
            String branch = String.valueOf(body.getOrDefault("branch", "")).trim();
            boolean create = Boolean.TRUE.equals(body.get("create"));
            if (branch.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("detail", "branch is required"));
            }
            return ResponseEntity.ok(gitService.checkout(resolveDir(request), branch, create));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @GetMapping("/diff")
    public ResponseEntity<?> diff(@RequestParam(required = false) String path,
                                  @RequestParam(defaultValue = "false") boolean staged,
                                  @RequestParam(defaultValue = "false") boolean untracked,
                                  HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.diff(resolveDir(request), path, staged, untracked));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/stage")
    public ResponseEntity<?> stage(@RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.stage(resolveDir(request), pathList(body)));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/unstage")
    public ResponseEntity<?> unstage(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.unstage(resolveDir(request), pathList(body)));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/commit")
    public ResponseEntity<?> commit(@RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        try {
            String message = String.valueOf(body.getOrDefault("message", ""));
            return ResponseEntity.ok(gitService.commit(resolveDir(request), message));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @PostMapping("/discard")
    public ResponseEntity<?> discard(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.discard(resolveDir(request), pathList(body)));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @GetMapping("/commit-diff")
    public ResponseEntity<?> commitDiff(@RequestParam String commit_hash,
                                        HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.commitDiff(resolveDir(request), commit_hash));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @GetMapping("/log")
    public ResponseEntity<?> log(@RequestParam(defaultValue = "20") int limit,
                                 HttpServletRequest request) {
        try {
            return ResponseEntity.ok(gitService.log(resolveDir(request), limit));
        } catch (Exception e) {
            return handle(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> pathList(Map<String, Object> body) {
        if (body == null || !(body.get("paths") instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
