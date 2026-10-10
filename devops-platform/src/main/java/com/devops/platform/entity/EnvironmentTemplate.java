package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 环境模板 - 预设的 K8s 资源/网络/资源配额
 * <p>
 * 用户克隆模板创建环境, 不用每次手动配
 * 内置 4 套: dev / test / staging / prod
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "environment_templates", indexes = {
        @Index(name = "idx_envtmpl_type", columnList = "templateType"),
        @Index(name = "idx_envtmpl_builtin", columnList = "builtin")
})
public class EnvironmentTemplate {

    /** 内置模板: dev / test / staging / prod / custom */
    public static final String TYPE_DEV = "dev";
    public static final String TYPE_TEST = "test";
    public static final String TYPE_STAGING = "staging";
    public static final String TYPE_PROD = "prod";
    public static final String TYPE_CUSTOM = "custom";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;  // e.g. "标准开发环境"

    @Column(nullable = false, length = 50)
    private String templateType;  // dev/test/staging/prod/custom

    @Column(length = 500)
    private String description;

    @Column(length = 200)
    private String k8sNamespacePattern;  // e.g. "{projectCode}-dev"

    @Column(length = 100)
    private String k8sCluster;

    @Column(length = 16)
    private String cpuRequest;  // e.g. "100m"

    @Column(length = 16)
    private String cpuLimit;    // e.g. "500m"

    @Column(length = 16)
    private String memoryRequest;  // e.g. "256Mi"

    @Column(length = 16)
    private String memoryLimit;    // e.g. "1Gi"

    @Column(nullable = false)
    private Integer replicas = 1;

    @Column(nullable = false)
    private Boolean protectedEnv = false;

    @Column(length = 16)
    private String color = "#378ADD";

    @Column(nullable = false)
    private Boolean builtin = false;  // 内置模板不可删

    @Column(length = 200)
    private String ingressHostPattern;  // e.g. "{projectCode}-dev.example.com"

    @Column(nullable = false)
    private Integer sortOrder = 0;  // 排序权重

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
