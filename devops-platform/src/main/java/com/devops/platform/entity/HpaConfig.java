package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * HPA (Horizontal Pod Autoscaler) 配置 — 弹性伸缩
 */
@Data
@Entity
@Table(name = "hpa_configs")
public class HpaConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Long projectId;

    @Column(length = 200)
    private String targetDeployment;   // 目标 Deployment 名称

    @Column(length = 100)
    private String namespace = "devops";

    @Column
    private Integer minReplicas = 1;

    @Column
    private Integer maxReplicas = 10;

    @Column
    private Integer targetCpuPercent = 70;     // 目标 CPU 使用率%

    @Column
    private Integer targetMemoryPercent = 80;  // 目标内存使用率%

    @Column(length = 500)
    private String customMetrics;      // JSON: 自定义指标配置

    @Column
    private Boolean enabled = true;

    @Column(length = 20)
    private String status = "INACTIVE";  // ACTIVE / INACTIVE / ERROR

    @Column
    private Integer currentReplicas;

    @Column
    private Double currentCpuPercent;

    @Column
    private Double currentMemoryPercent;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
