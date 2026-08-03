package com.agent.coding.repository;

import com.agent.coding.entity.ProviderModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProviderModelRepository extends JpaRepository<ProviderModelEntity, Long> {
    List<ProviderModelEntity> findByProviderId(String providerId);
    Optional<ProviderModelEntity> findByProviderIdAndModelId(String providerId, String modelId);
    void deleteByProviderId(String providerId);
}
