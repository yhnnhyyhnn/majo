package com.agent.coding.controller;

import com.agent.coding.ChatService;
import com.agent.coding.dto.ChatHistoryDto;
import com.agent.coding.dto.ChatSpecDto;
import com.agent.coding.dto.DeletedResponse;
import com.agent.coding.dto.StatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(required = false) Boolean archived) {
        return service.list(user_id, channel, archived).stream()
            .map(ChatSpecDto::from).toList();
    }

    @PostMapping("/chats")
    public ChatSpecDto create() {
        return ChatSpecDto.from(service.create());
    }

    @GetMapping("/chats/{id}")
    public Object getChat(@PathVariable String id) {
        var chat = service.getChat(id);
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
}
