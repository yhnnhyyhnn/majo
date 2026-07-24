package com.agent.coding.repository;

import com.agent.coding.entity.SettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SettingsRepository extends JpaRepository<SettingsEntity, Integer> {
}
