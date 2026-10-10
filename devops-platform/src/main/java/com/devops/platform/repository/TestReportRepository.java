package com.devops.platform.repository;

import com.devops.platform.entity.TestReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestReportRepository extends JpaRepository<TestReport, Long> {

    /** 按构建 ID 查找（一个构建可能有多份报告，如多模块） */
    List<TestReport> findByBuildId(Long buildId);

    /** 按项目 ID 查找所有测试报告 */
    List<TestReport> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 查找构建的最新一份报告 */
    Optional<TestReport> findFirstByBuildIdOrderByCreatedAtDesc(Long buildId);

    /** 按项目统计平均覆盖率 */
    @Query("SELECT AVG(t.coveragePercent) FROM TestReport t WHERE t.projectId = :projectId AND t.coveragePercent >= 0")
    Double avgCoverageByProjectId(@Param("projectId") Long projectId);

    /** 按项目统计最近通过率 */
    @Query("SELECT t FROM TestReport t WHERE t.projectId = :projectId ORDER BY t.createdAt DESC")
    List<TestReport> findRecentByProjectId(@Param("projectId") Long projectId);

    /** 质量门失败的报告 */
    List<TestReport> findByBuildIdAndQualityGatePassed(Long buildId, Boolean qualityGatePassed);
}
