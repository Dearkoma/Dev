package com.devops.platform.repository;

import com.devops.platform.entity.ClusterConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ClusterConfigRepository extends JpaRepository<ClusterConfig, Long> {
    Optional<ClusterConfig> findByName(String name);
    Optional<ClusterConfig> findByIsDefaultTrue();
}
