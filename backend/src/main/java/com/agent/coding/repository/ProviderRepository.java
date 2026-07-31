package com.agent.coding.repository;

import com.agent.coding.entity.ProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProviderRepository extends JpaRepository<ProviderEntity, String> {
    List<ProviderEntity> findAllByOrderByNameAsc();
}
