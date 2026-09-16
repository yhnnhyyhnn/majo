package com.agent.coding.repository;

import com.agent.coding.entity.TurnUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TurnUsageRepository extends JpaRepository<TurnUsageEntity, Long> {

    List<TurnUsageEntity> findByAgentIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            String agentId, LocalDateTime since);

    @Query("SELECT t.agentId, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t), "
         + "SUM(t.durationMs) FROM TurnUsageEntity t "
         + "WHERE t.createdAt >= :since GROUP BY t.agentId ORDER BY SUM(t.inputTokens) DESC")
    List<Object[]> aggregateByAgentSince(@Param("since") LocalDateTime since);
}
