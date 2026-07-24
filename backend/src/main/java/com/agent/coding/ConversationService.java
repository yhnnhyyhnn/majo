package com.agent.coding;

import com.agent.coding.entity.ConversationEntity;
import com.agent.coding.entity.MessageEntity;
import com.agent.coding.repository.ConversationRepository;
import com.agent.coding.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository convRepo;
    private final MessageRepository msgRepo;

    public ConversationService(ConversationRepository convRepo, MessageRepository msgRepo) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
    }

    public List<ConversationEntity> list() {
        return convRepo.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public ConversationEntity create() {
        var conv = new ConversationEntity();
        conv.setId(UUID.randomUUID().toString());
        conv.setTitle("New Chat");
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        return convRepo.save(conv);
    }

    public List<MessageEntity> getMessages(String conversationId) {
        return msgRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public void saveMessages(String conversationId, List<Map<String, String>> messages) {
        if (messages.isEmpty()) return;
        convRepo.findById(conversationId).ifPresent(conv -> {
            conv.setUpdatedAt(LocalDateTime.now());
            convRepo.save(conv);
        });
        for (Map<String, String> m : messages) {
            var entity = new MessageEntity();
            entity.setConversationId(conversationId);
            entity.setRole(m.get("role"));
            entity.setContent(m.getOrDefault("content", ""));
            entity.setMetadata(m.getOrDefault("metadata", null));
            entity.setCreatedAt(LocalDateTime.now());
            msgRepo.save(entity);
        }
        // Auto-title: first user message
        if (msgRepo.countByConversationId(conversationId) == messages.size()) {
            var firstUserMsg = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .findFirst();
            if (firstUserMsg.isPresent()) {
                String content = firstUserMsg.get().get("content");
                String title = content.length() > 40 ? content.substring(0, 40) + "..." : content;
                convRepo.findById(conversationId).ifPresent(conv -> {
                    conv.setTitle(title);
                    convRepo.save(conv);
                });
            }
        }
    }

    @Transactional
    public void delete(String conversationId) {
        convRepo.deleteById(conversationId);
    }
}
