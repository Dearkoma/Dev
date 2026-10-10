package com.devops.platform.repository;

import com.devops.platform.entity.SastScanReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SastScanReportRepository extends JpaRepository<SastScanReport, Long> {
    List<SastScanReport> findByBuildId(Long buildId);
    List<SastScanReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
