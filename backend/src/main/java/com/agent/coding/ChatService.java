package com.agent.coding;

import com.agent.coding.entity.ChatEntity;
import com.agent.coding.entity.MessageEntity;
import com.agent.coding.repository.ChatRepository;
import com.agent.coding.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatService {

    private final ChatRepository chatRepo;
    private final MessageRepository msgRepo;

    public ChatService(ChatRepository chatRepo, MessageRepository msgRepo) {
        this.chatRepo = chatRepo;
        this.msgRepo = msgRepo;
    }

    public List<ChatEntity> list() {
        return chatRepo.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public ChatEntity create() {
        var chat = new ChatEntity();
        chat.setId(UUID.randomUUID().toString());
        chat.setTitle("New Chat");
        chat.setStatus("idle");
        chat.setCreatedAt(LocalDateTime.now());
        chat.setUpdatedAt(LocalDateTime.now());
        return chatRepo.save(chat);
    }

    public List<MessageEntity> getMessages(String chatId) {
        return msgRepo.findByChatIdOrderByCreatedAtAsc(chatId);
    }

    @Transactional
    public ChatEntity getChat(String chatId) {
        return chatRepo.findById(chatId).orElse(null);
    }

    @Transactional
    public void setStatus(String chatId, String status) {
        chatRepo.findById(chatId).ifPresent(chat -> {
            chat.setStatus(status);
            chat.setUpdatedAt(LocalDateTime.now());
            chatRepo.save(chat);
        });
    }

    @Transactional
    public void saveMessages(String chatId, List<Map<String, String>> messages) {
        if (messages.isEmpty()) return;
        chatRepo.findById(chatId).ifPresent(chat -> {
            chat.setUpdatedAt(LocalDateTime.now());
            chat.setStatus("idle");
            chatRepo.save(chat);
        });
        for (Map<String, String> m : messages) {
            var entity = new MessageEntity();
            entity.setChatId(chatId);
            entity.setRole(m.get("role"));
            entity.setContent(m.getOrDefault("content", ""));
            entity.setToolCalls(m.getOrDefault("toolCalls", null));
            entity.setThinking(m.getOrDefault("thinking", null));
            entity.setCreatedAt(LocalDateTime.now());
            msgRepo.save(entity);
        }
        // Auto-title from first user message
        if (msgRepo.countByChatId(chatId) == messages.size()) {
            var firstUserMsg = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .findFirst();
            firstUserMsg.ifPresent(m -> {
                String content = m.get("content");
                String title = content.length() > 40 ? content.substring(0, 40) + "..." : content;
                chatRepo.findById(chatId).ifPresent(chat -> {
                    chat.setTitle(title);
                    chatRepo.save(chat);
                });
            });
        }
    }

    @Transactional
    public void delete(String chatId) {
        chatRepo.deleteById(chatId);
    }

    @Transactional
    public ChatEntity rename(String chatId, String title) {
        var chat = chatRepo.findById(chatId).orElse(null);
        if (chat != null) {
            chat.setTitle(title);
            chat.setUpdatedAt(LocalDateTime.now());
            chatRepo.save(chat);
        }
        return chat;
    }
}
