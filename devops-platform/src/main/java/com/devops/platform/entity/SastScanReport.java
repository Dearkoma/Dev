package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * SAST 静态应用安全测试报告 — Semgrep / SonarQube
 */
@Data
@Entity
@Table(name = "sast_scan_reports")
public class SastScanReport {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long buildId;

    @Column
    private Long projectId;

    @Column(length = 30)
    private String scanner = "SEMGREP";  // SEMGREP / SONARQUBE

    @Column
    private Integer totalFindings = 0;

    @Column
    private Integer criticalCount = 0;

    @Column
    private Integer highCount = 0;

    @Column
    private Integer mediumCount = 0;

    @Column
    private Integer lowCount = 0;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String findingsJson;       // 完整漏洞列表 JSON

    @Column(length = 20)
    private String status = "COMPLETED";  // COMPLETED / FAILED

    @Column(length = 500)
    private String summary;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
