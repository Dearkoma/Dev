package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 测试报告实体 — 记录每次构建的测试执行结果
 * 支持 JUnit/Jest/pytest/go test/Cargo test/CTest 等多框架
 */
@Data
@Entity
@Table(name = "test_reports")
public class TestReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long buildId;

    @Column
    private Long projectId;

    @Column
    private Long pipelineId;

    /** 测试框架: JUNIT / JEST / PYTEST / GO_TEST / CARGO_TEST / CTEST / UNKNOWN */
    @Column(length = 30)
    private String testFramework;

    /** 总测试数 */
    @Column
    private Integer totalTests = 0;

    /** 通过数 */
    @Column
    private Integer passed = 0;

    /** 失败数 */
    @Column
    private Integer failed = 0;

    /** 跳过数 */
    @Column
    private Integer skipped = 0;

    /** 错误数（框架级错误，非断言失败） */
    @Column
    private Integer errors = 0;

    /** 代码覆盖率百分比 (0.0 ~ 100.0)，-1 表示未采集 */
    @Column
    private Double coveragePercent = -1.0;

    /** 行覆盖率 */
    @Column
    private Double lineCoverage = -1.0;

    /** 分支覆盖率 */
    @Column
    private Double branchCoverage = -1.0;

    /** 测试执行耗时（毫秒） */
    @Column
    private Long durationMs;

    /** 简要摘要 */
    @Column(length = 1000)
    private String summary;

    /** 原始报告 XML/JSON（用于前端详情查看，MEDIUMTEXT 约 16MB） */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String rawReport;

    /** 质量门是否通过 */
    @Column
    private Boolean qualityGatePassed = true;

    /** 质量门失败原因 */
    @Column(length = 500)
    private String qualityGateMessage;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
