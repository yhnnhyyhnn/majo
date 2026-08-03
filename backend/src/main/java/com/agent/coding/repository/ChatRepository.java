package com.agent.coding.repository;

import com.agent.coding.entity.ChatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRepository extends JpaRepository<ChatEntity, String> {
    List<ChatEntity> findAllByOrderByUpdatedAtDesc();
    List<ChatEntity> findAllByArchivedAtIsNullOrderByUpdatedAtDesc();
    List<ChatEntity> findAllByArchivedAtIsNotNullOrderByUpdatedAtDesc();
    Optional<ChatEntity> findBySessionId(String sessionId);
}
