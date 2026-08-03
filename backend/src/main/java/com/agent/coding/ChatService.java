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
import java.util.Objects;
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

    public List<ChatEntity> list(String userId, String channel, Boolean archived) {
        if (Boolean.TRUE.equals(archived)) {
            return chatRepo.findAllByArchivedAtIsNotNullOrderByUpdatedAtDesc();
        }
        if (Boolean.FALSE.equals(archived)) {
            return chatRepo.findAllByArchivedAtIsNullOrderByUpdatedAtDesc();
        }
        return chatRepo.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public ChatEntity getOrCreateBySession(String sessionId, String firstPrompt) {
        var existing = chatRepo.findBySessionId(sessionId);
        if (existing.isPresent()) {
            var chat = existing.get();
            chat.setUpdatedAt(LocalDateTime.now());
            return chatRepo.save(chat);
        }
        var chat = new ChatEntity();
        chat.setId(UUID.randomUUID().toString());
        chat.setSessionId(sessionId);
        // Placeholder: first 10 chars (qwenpaw: _extract_placeholder_name)
        String title = "New Chat";
        if (firstPrompt != null && !firstPrompt.isBlank()) {
            title = firstPrompt.length() > 10 ? firstPrompt.substring(0, 10) : firstPrompt;
        }
        chat.setTitle(title);
        chat.setStatus("idle");
        chat.setCreatedAt(LocalDateTime.now());
        chat.setUpdatedAt(LocalDateTime.now());
        return chatRepo.save(chat);
    }

    @Transactional
    public boolean patchChatIfNameMatches(String chatId, String expectedTitle, String newTitle) {
        var chat = chatRepo.findById(chatId).orElse(null);
        if (chat == null) return false;
        if (!Objects.equals(chat.getTitle(), expectedTitle)) return false;
        chat.setTitle(newTitle);
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepo.save(chat);
        return true;
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

    @Transactional
    public ChatEntity create(ChatEntity entity) {
        if (entity.getCreatedAt() == null) entity.setCreatedAt(LocalDateTime.now());
        if (entity.getUpdatedAt() == null) entity.setUpdatedAt(LocalDateTime.now());
        return chatRepo.save(entity);
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

    @Transactional
    public void setArchived(String chatId, boolean archived) {
        chatRepo.findById(chatId).ifPresent(chat -> {
            chat.setArchivedAt(archived ? LocalDateTime.now() : null);
            chat.setUpdatedAt(LocalDateTime.now());
            chatRepo.save(chat);
        });
    }
}
