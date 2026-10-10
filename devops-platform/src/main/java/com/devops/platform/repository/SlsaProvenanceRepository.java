package com.devops.platform.repository;

import com.devops.platform.entity.SlsaProvenance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SlsaProvenanceRepository extends JpaRepository<SlsaProvenance, Long> {
    Optional<SlsaProvenance> findByBuildId(Long buildId);
    List<SlsaProvenance> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
