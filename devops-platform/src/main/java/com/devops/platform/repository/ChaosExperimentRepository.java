package com.devops.platform.repository;

import com.devops.platform.entity.ChaosExperiment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChaosExperimentRepository extends JpaRepository<ChaosExperiment, Long> {
    List<ChaosExperiment> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<ChaosExperiment> findByStatus(String status);
}
