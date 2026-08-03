package com.agent.coding.repository;

import com.agent.coding.entity.ActiveModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ActiveModelRepository extends JpaRepository<ActiveModelEntity, Long> {

    Optional<ActiveModelEntity> findByScopeAndAgentId(String scope, String agentId);

    @Query("SELECT a FROM ActiveModelEntity a WHERE a.scope = 'global' AND a.agentId IS NULL")
    Optional<ActiveModelEntity> findGlobal();
}
