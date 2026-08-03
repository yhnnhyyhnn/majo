package com.agent.coding.controller;

import com.agent.coding.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatsController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ChatService service;

    public ChatsController(ChatService service) {
        this.service = service;
    }

    private Map<String, Object> toChatSpec(com.agent.coding.entity.ChatEntity c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("session_id", c.getSessionId().isEmpty() ? ("console:" + c.getId()) : c.getSessionId());
        map.put("user_id", c.getUserId());
        map.put("channel", c.getChannel());
        map.put("name", c.getTitle());
        map.put("status", c.getStatus());
        map.put("created_at", c.getCreatedAt() != null ? c.getCreatedAt().format(ISO) : null);
        map.put("updated_at", c.getUpdatedAt() != null ? c.getUpdatedAt().format(ISO) : null);
        map.put("pinned", c.getPinned());
        map.put("archived", c.getArchivedAt() != null);
        map.put("archived_at", c.getArchivedAt() != null ? c.getArchivedAt().format(ISO) : null);
        map.put("meta", new LinkedHashMap<>());
        return map;
    }

    @GetMapping("/chats")
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String user_id,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Boolean archived) {
        var chats = service.list(user_id, channel, archived);
        return chats.stream().map(this::toChatSpec).toList();
    }

    private static String resolveMessageType(com.agent.coding.entity.MessageEntity m) {
        if ("user".equals(m.getRole())) return "message";
        if (m.getThinking() != null && !m.getThinking().isBlank()) return "reasoning";
        return "message";
    }

    @PostMapping("/chats")
    public Map<String, Object> create() {
        var chat = service.create();
        return toChatSpec(chat);
    }

    @GetMapping("/chats/{id}")
    public Map<String, Object> getChat(@PathVariable String id) {
        var chat = service.getChat(id);
        if (chat == null) return Map.of("error", "not found");
        var messages = service.getMessages(id).stream()
            .map(m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", m.getId().toString());
                map.put("type", resolveMessageType(m));
                map.put("role", m.getRole());
                // Parse content as JSON array if possible, else wrap in text array
                Object parsed = tryParseJson(m.getContent());
                map.put("content", parsed != null ? parsed : m.getContent());
                map.put("status", "completed");
                map.put("created_at", m.getCreatedAt() != null ? m.getCreatedAt().format(ISO) : null);
                map.put("metadata", m.getToolCalls() != null ? Map.of("toolCalls", m.getToolCalls()) : Map.of());
                return map;
            })
            .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messages);
        result.put("status", chat.getStatus());
        return result;
    }

    private static Object tryParseJson(String s) {
        if (s == null || s.isBlank() || !s.trim().startsWith("[")) return null;
        try {
            return new ObjectMapper().readValue(s, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    @PostMapping("/chats/{id}/messages")
    public Map<String, String> saveMessages(
            @PathVariable String id,
            @RequestBody List<Map<String, String>> messages) {
        service.saveMessages(id, messages);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/chats/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("deleted", true);
    }

    @PatchMapping("/chats/{id}")
    public Map<String, Object> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newName = body.getOrDefault("name", body.get("title"));
        var chat = service.rename(id, newName != null ? newName : "New Chat");
        if (chat == null) return Map.of("error", "not found");
        return toChatSpec(chat);
    }

    @Transactional
    @PutMapping("/chats/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newName = body.getOrDefault("name", body.get("title")); // frontend sends "name"
        if (newName != null && !newName.isBlank()) service.rename(id, newName);
        if (body.containsKey("pinned")) {
            var chat = service.getChat(id);
            if (chat != null) { chat.setPinned(Boolean.valueOf(body.get("pinned"))); }
        }
        var chat = service.getChat(id);
        return chat != null ? toChatSpec(chat) : Map.of("error", "not found");
    }

    @Transactional
    @PostMapping("/chats/{id}/archive")
    public Map<String, Object> archive(@PathVariable String id) {
        service.setArchived(id, true);
        return toChatSpec(service.getChat(id));
    }

    @Transactional
    @PostMapping("/chats/{id}/unarchive")
    public Map<String, Object> unarchive(@PathVariable String id) {
        service.setArchived(id, false);
        return toChatSpec(service.getChat(id));
    }

    @Transactional
    @PostMapping("/chats/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody List<String> ids) {
        if (ids != null) ids.forEach(service::delete);
        return Map.of("status", "ok", "deleted_count", ids != null ? ids.size() : 0);
    }

    @Transactional
    @PostMapping("/chats/actions/batch-archive")
    public Map<String, Object> batchArchive(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var ids = (List<String>) body.getOrDefault("chat_ids", List.of());
        ids.forEach(id -> service.setArchived(id, true));
        return Map.of("status", "ok");
    }

    @Transactional
    @PostMapping("/chats/actions/batch-unarchive")
    public Map<String, Object> batchUnarchive(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var ids = (List<String>) body.getOrDefault("chat_ids", List.of());
        ids.forEach(id -> service.setArchived(id, false));
        return Map.of("status", "ok");
    }
}
