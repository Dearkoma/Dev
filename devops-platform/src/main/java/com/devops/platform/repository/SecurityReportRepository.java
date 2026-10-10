package com.devops.platform.repository;

import com.devops.platform.entity.SecurityReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecurityReportRepository extends JpaRepository<SecurityReport, Long> {

    List<SecurityReport> findByBuildIdOrderByScannedAtDesc(Long buildId);

    List<SecurityReport> findByProjectIdOrderByScannedAtDesc(Long projectId);

    /**
     * 项目最新一次扫描
     */
    @Query("SELECT r FROM SecurityReport r WHERE r.projectId = :projectId ORDER BY r.scannedAt DESC")
    List<SecurityReport> findLatestByProject(@Param("projectId") Long projectId);

    /**
     * 失败 (status = FAILED) 的扫描
     */
    List<SecurityReport> findByProjectIdAndStatusOrderByScannedAtDesc(Long projectId, String status);

    /**
     * 统计项目下各严重程度漏洞数量(最近一次扫描)
     */
    @Query("SELECT r.criticalCount, r.highCount, r.mediumCount, r.lowCount FROM SecurityReport r " +
            "WHERE r.projectId = :projectId ORDER BY r.scannedAt DESC")
    List<Object[]> countByProjectLatest(@Param("projectId") Long projectId);
}
