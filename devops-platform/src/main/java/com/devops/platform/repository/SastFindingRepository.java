package com.devops.platform.repository;

import com.devops.platform.entity.SastFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SastFindingRepository extends JpaRepository<SastFinding, Long> {
    List<SastFinding> findByReportId(Long reportId);
    List<SastFinding> findByProjectIdOrderByIdDesc(Long projectId);
    List<SastFinding> findByBuildId(Long buildId);
}
