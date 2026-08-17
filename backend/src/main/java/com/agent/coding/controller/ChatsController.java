package com.agent.coding.controller;

import com.agent.coding.ChatService;
import com.agent.coding.dto.ChatHistoryDto;
import com.agent.coding.dto.ChatSpecDto;
import com.agent.coding.dto.DeletedResponse;
import com.agent.coding.dto.StatusResponse;
import com.agent.coding.entity.ChatEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatsController {

    private final ChatService service;

    public ChatsController(ChatService service) {
        this.service = service;
    }

    @GetMapping("/chats")
    public List<ChatSpecDto> list(
            @RequestParam(required = false) String user_id,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Boolean archived,
            HttpServletRequest request) {
        String agentId = request.getHeader("X-Agent-Id");
        return (agentId != null && !agentId.isBlank()
            ? service.listByAgent(agentId, archived)
            : service.list(user_id, channel, archived)).stream()
            .map(ChatSpecDto::from).toList();
    }

    @PostMapping("/chats")
    public ChatSpecDto create() {
        return ChatSpecDto.from(service.create());
    }

    /** Effective session project directory: session override > agent > workspace. */
    private Map<String, Object> projectDirResponse(ChatEntity chat) {
        String sessionOverride = chat.getProjectDir();
        String agentDir = com.agent.coding.agent.AgentStore.getProjectDir(chat.getAgentId());
        String projectDir;
        String source;
        if (sessionOverride != null && !sessionOverride.isBlank()) {
            projectDir = sessionOverride;
            source = "session";
        } else if (agentDir != null && !agentDir.isBlank()) {
            projectDir = agentDir;
            source = "agent";
        } else {
            projectDir = com.agent.coding.agent.AgentStore.workspaceDirForAgent(chat.getAgentId()).toString();
            source = "workspace_fallback";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_dir", projectDir);
        result.put("source", source);
        result.put("agent_project_dir", agentDir);
        result.put("exists", java.nio.file.Files.isDirectory(java.nio.file.Path.of(projectDir)));
        return result;
    }

    @GetMapping("/chats/{id}/project-dir")
    public ResponseEntity<Map<String, Object>> getChatProjectDir(@PathVariable String id) {
        ChatEntity chat = service.getChat(id);
        if (chat == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "Chat not found: " + id));
        }
        return ResponseEntity.ok(projectDirResponse(chat));
    }

    @PutMapping("/chats/{id}/project-dir")
    public ResponseEntity<Map<String, Object>> setChatProjectDir(@PathVariable String id,
                                                                 @RequestBody Map<String, Object> body) {
        String projectDir = body.get("project_dir") == null ? "" : String.valueOf(body.get("project_dir"));
        if (projectDir.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "project_dir is required"));
        }
        java.nio.file.Path target = java.nio.file.Path.of(projectDir).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(target)) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Project directory is unavailable: " + target));
        }
        ChatEntity chat = service.setProjectDir(id, target.toString());
        if (chat == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "Chat not found: " + id));
        }
        return ResponseEntity.ok(projectDirResponse(chat));
    }

    @DeleteMapping("/chats/{id}/project-dir")
    public ResponseEntity<Map<String, Object>> clearChatProjectDir(@PathVariable String id) {
        ChatEntity chat = service.setProjectDir(id, null);
        if (chat == null) {
            return ResponseEntity.status(404).body(Map.of("detail", "Chat not found: " + id));
        }
        return ResponseEntity.ok(projectDirResponse(chat));
    }

    @GetMapping("/chats/{id}")
    public Object getChat(@PathVariable String id) {        var chat = service.getChat(id);
        if (chat == null) return Map.of("error", "not found");
        var messages = service.getMessages(id).stream()
            .map(m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", m.getId().toString());
                map.put("type", resolveMessageType(m));
                map.put("role", m.getRole());
                Object parsed = tryParseJson(m.getContent());
                map.put("content", parsed != null ? parsed : m.getContent());
                map.put("status", "completed");
                map.put("created_at", m.getCreatedAt() != null ? m.getCreatedAt().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
                map.put("metadata", m.getToolCalls() != null ? Map.of("toolCalls", m.getToolCalls()) : Map.of());
                return map;
            })
            .toList();
        return new ChatHistoryDto(messages, chat.getStatus());
    }

    private static String resolveMessageType(com.agent.coding.entity.MessageEntity m) {
        if ("user".equals(m.getRole())) return "message";
        if (m.getThinking() != null && !m.getThinking().isBlank()) return "reasoning";
        return "message";
    }

    private static Object tryParseJson(String s) {
        if (s == null || s.isBlank() || !s.trim().startsWith("[")) return null;
        try { return new ObjectMapper().readValue(s, List.class); } catch (Exception e) { return null; }
    }

    @PostMapping("/chats/{id}/messages")
    public StatusResponse saveMessages(@PathVariable String id,
                                        @RequestBody List<Map<String, String>> messages) {
        service.saveMessages(id, messages);
        return StatusResponse.ok();
    }

    @DeleteMapping("/chats/{id}")
    public DeletedResponse delete(@PathVariable String id) {
        service.delete(id);
        return DeletedResponse.ok();
    }

    @PatchMapping("/chats/{id}")
    public Object rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newName = body.getOrDefault("name", body.get("title"));
        var chat = service.rename(id, newName != null ? newName : "New Chat");
        if (chat == null) return Map.of("error", "not found");
        return ChatSpecDto.from(chat);
    }

    @Transactional
    @PutMapping("/chats/{id}")
    public Object update(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newName = body.getOrDefault("name", body.get("title"));
        if (newName != null && !newName.isBlank()) service.rename(id, newName);
        if (body.containsKey("pinned")) {
            var chat = service.getChat(id);
            if (chat != null) chat.setPinned(Boolean.valueOf(body.get("pinned")));
        }
        var chat = service.getChat(id);
        return chat != null ? ChatSpecDto.from(chat) : Map.of("error", "not found");
    }

    @Transactional
    @PostMapping("/chats/{id}/archive")
    public ChatSpecDto archive(@PathVariable String id) {
        service.setArchived(id, true);
        return ChatSpecDto.from(service.getChat(id));
    }

    @Transactional
    @PostMapping("/chats/{id}/unarchive")
    public ChatSpecDto unarchive(@PathVariable String id) {
        service.setArchived(id, false);
        return ChatSpecDto.from(service.getChat(id));
    }

    @Transactional
    @PostMapping("/chats/batch-delete")
    public StatusResponse batchDelete(@RequestBody List<String> ids) {
        if (ids != null) ids.forEach(service::delete);
        return StatusResponse.ok();
    }

    @Transactional
    @PostMapping("/chats/actions/batch-archive")
    public StatusResponse batchArchive(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var ids = (List<String>) body.getOrDefault("chat_ids", List.of());
        ids.forEach(id -> service.setArchived(id, true));
        return StatusResponse.ok();
    }

    @Transactional
    @PostMapping("/chats/actions/batch-unarchive")
    public StatusResponse batchUnarchive(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var ids = (List<String>) body.getOrDefault("chat_ids", List.of());
        ids.forEach(id -> service.setArchived(id, false));
        return StatusResponse.ok();
    }

    // ── Agent-scoped chats (port of qwenpaw agent_scoped /agents/{agentId}/chats) ──
    @PostMapping("/agents/{agentId}/chats/actions/batch-archive")
    public Object agentBatchArchive(@PathVariable String agentId,
                                    @RequestBody Map<String, Object> body) { return batchArchive(body); }

    @PostMapping("/agents/{agentId}/chats/actions/batch-unarchive")
    public Object agentBatchUnarchive(@PathVariable String agentId,
                                      @RequestBody Map<String, Object> body) { return batchUnarchive(body); }
}
