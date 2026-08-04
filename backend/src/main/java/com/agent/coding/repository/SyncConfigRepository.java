package com.agent.coding.repository;

import com.agent.coding.entity.SyncConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncConfigRepository extends JpaRepository<SyncConfigEntity, String> {
}
