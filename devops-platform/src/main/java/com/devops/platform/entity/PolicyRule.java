package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * OPA / 策略即代码规则 — 部署前合规检查
 * Rego 策略评估: 镜像来源、资源限制、安全上下文等
 */
@Data
@Entity
@Table(name = "policy_rules")
public class PolicyRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 30)
    private String category = "SECURITY";  // SECURITY / COMPLIANCE / RESOURCE / CUSTOM

    @Column(length = 20)
    private String severity = "WARN";      // DENY / WARN / AUDIT

    @Column(columnDefinition = "TEXT")
    private String regoPolicy;             // Rego 策略源码

    @Column
    private Boolean enabled = true;

    @Column
    private Long projectId;                // null = 全局策略

    @Column(length = 20)
    private String targetStage = "DEPLOY"; // BUILD / DEPLOY / OPERATE

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;
}
