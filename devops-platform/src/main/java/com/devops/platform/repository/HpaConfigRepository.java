package com.devops.platform.repository;

import com.devops.platform.entity.HpaConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HpaConfigRepository extends JpaRepository<HpaConfig, Long> {
    List<HpaConfig> findByProjectId(Long projectId);
    List<HpaConfig> findByEnabledTrue();
}
