package com.agent.coding.repository;

import com.agent.coding.entity.EnvVarEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvVarRepository extends JpaRepository<EnvVarEntity, String> {}
