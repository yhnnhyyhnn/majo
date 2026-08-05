package com.agent.coding.repository;

import com.agent.coding.entity.PluginCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface PluginCacheRepository extends JpaRepository<PluginCacheEntity, String> {

    List<PluginCacheEntity> findBySourceOrderByNameAsc(String source);

    @Query("SELECT p FROM PluginCacheEntity p WHERE p.source = :source "
         + "AND (:search IS NULL OR LOWER(p.name) LIKE %:search% OR LOWER(p.displayName) LIKE %:search% OR LOWER(p.description) LIKE %:search%) "
         + "AND (:category IS NULL OR p.category = :category) "
         + "ORDER BY p.name ASC")
    List<PluginCacheEntity> search(@Param("source") String source,
                                    @Param("search") String search,
                                    @Param("category") String category);

    @Transactional
    void deleteBySource(String source);
}
