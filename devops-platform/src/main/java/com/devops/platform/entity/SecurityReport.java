package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 安全扫描报告 - 一次构建的安全扫描结果
 * <p>
 * Trivy 输出按严重程度分: CRITICAL / HIGH / MEDIUM / LOW / UNKNOWN
 * 一次构建可能同时扫 fs / pom.xml / image → 三个 target 合并到一条 report
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "security_reports", indexes = {
        @Index(name = "idx_security_build", columnList = "buildId"),
        @Index(name = "idx_security_project", columnList = "projectId"),
        @Index(name = "idx_security_status", columnList = "status")
})
public class SecurityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联构建 ID (手动扫描时为 null) */
    @Column(nullable = true)
    private Long buildId;

    /** 关联项目 ID (冗余,方便聚合查询) */
    @Column(nullable = false)
    private Long projectId;

    /** 扫描类型: FS / DEPENDENCY / IMAGE */
    @Column(nullable = false, length = 32)
    private String scanType;

    /** 扫描目标 - fs 路径 / pom.xml / image:tag */
    @Column(length = 512)
    private String target;

    /** 整体状态: PASSED / FAILED / ERRORED */
    @Column(nullable = false, length = 32)
    private String status;

    /** 阻断阈值: 当 high+critical 超过此值时 FAILED */
    @Column(nullable = false)
    private Integer thresholdHigh = 5;

    @Column(nullable = false)
    private Integer thresholdCritical = 0;

    /** 漏洞计数 */
    @Column(nullable = false) private Integer criticalCount = 0;
    @Column(nullable = false) private Integer highCount = 0;
    @Column(nullable = false) private Integer mediumCount = 0;
    @Column(nullable = false) private Integer lowCount = 0;
    @Column(nullable = false) private Integer unknownCount = 0;
    @Column(nullable = false) private Integer totalCount = 0;

    /** 摘要 (前 50 个漏洞描述拼接) */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** 详细报告 JSON (Trivy 原生输出) */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String details;

    /** Trivy 命令原始输出(供排查, 超过 1MB 截断) */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String rawOutput;

    /** 扫描耗时 (ms) */
    private Long costMs;

    /** 扫描时间 */
    @Column(nullable = false)
    private LocalDateTime scannedAt = LocalDateTime.now();
}
