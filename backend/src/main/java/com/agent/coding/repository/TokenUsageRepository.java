package com.agent.coding.repository;

import com.agent.coding.entity.TokenUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, Long> {

    @Query("SELECT t FROM TokenUsageEntity t WHERE t.usageDate >= :start AND t.usageDate <= :end"
         + " ORDER BY t.usageDate, t.providerId, t.model")
    List<TokenUsageEntity> findByDateRange(@Param("start") String start, @Param("end") String end);
}
