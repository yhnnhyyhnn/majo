package com.agent.coding.controller;

import com.agent.coding.ConversationService;
import com.agent.coding.entity.ConversationEntity;
import com.agent.coding.entity.MessageEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> list() {
        return service.list().stream()
            .map(c -> Map.<String, Object>of(
                "id", c.getId(),
                "title", c.getTitle(),
                "updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : ""
            ))
            .toList();
    }

    @PostMapping("/conversations")
    public Map<String, String> create() {
        var conv = service.create();
        return Map.of("id", conv.getId());
    }

    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable String id) {
        return service.getMessages(id).stream()
            .map(m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", m.getId());
                map.put("role", m.getRole());
                map.put("content", m.getContent());
                map.put("time", m.getCreatedAt() != null ? m.getCreatedAt().toString() : "");
                if (m.getMetadata() != null && !m.getMetadata().isBlank()) {
                    map.put("metadata", m.getMetadata());
                }
                return map;
            })
            .toList();
    }

    @PostMapping("/conversations/{id}/messages")
    public Map<String, String> saveMessages(
            @PathVariable String id,
            @RequestBody List<Map<String, String>> messages) {
        service.saveMessages(id, messages);
        return Map.of("status", "ok");
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("status", "ok");
    }
}
