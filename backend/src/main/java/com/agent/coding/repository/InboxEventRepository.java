package com.agent.coding.repository;

import com.agent.coding.entity.InboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface InboxEventRepository extends JpaRepository<InboxEventEntity, String> {

    @Query("SELECT e FROM InboxEventEntity e ORDER BY e.createdAt DESC")
    List<InboxEventEntity> findAllOrderByCreatedAtDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM InboxEventEntity")
    void deleteAndKeepLatest(@Param("maxEvents") int maxEvents);

    @Modifying
    @Transactional
    @Query("UPDATE InboxEventEntity e SET e.read = true WHERE e.id IN :ids AND e.read = false")
    int markRead(@Param("ids") List<String> ids);

    @Modifying
    @Transactional
    @Query("UPDATE InboxEventEntity e SET e.read = true WHERE e.read = false")
    int markAllRead();
}
