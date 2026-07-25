package com.agent.coding.repository;

import com.agent.coding.entity.ModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModelConfigRepository extends JpaRepository<ModelConfigEntity, Long> {
}
