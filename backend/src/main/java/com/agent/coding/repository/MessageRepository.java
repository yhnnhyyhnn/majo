package com.agent.coding.repository;

import com.agent.coding.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByChatIdOrderByCreatedAtAsc(String chatId);
    long countByChatId(String chatId);
    void deleteByChatId(String chatId);
}
