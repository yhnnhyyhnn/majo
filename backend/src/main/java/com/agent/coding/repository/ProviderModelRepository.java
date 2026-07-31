package com.agent.coding.repository;

import com.agent.coding.entity.ProviderModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProviderModelRepository extends JpaRepository<ProviderModelEntity, Long> {
    List<ProviderModelEntity> findByProviderId(String providerId);
    void deleteByProviderId(String providerId);
}
