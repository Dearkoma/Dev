package com.devops.platform.repository;

import com.devops.platform.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {
    List<FeatureFlag> findByProjectId(Long projectId);
    Optional<FeatureFlag> findByKey(String key);
    List<FeatureFlag> findByProjectIdAndEnvironment(Long projectId, String environment);
}
