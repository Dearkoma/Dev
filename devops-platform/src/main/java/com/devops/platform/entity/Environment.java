package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 环境实体
 * 定义不同的部署环境（开发/测试/预发布/生产）
 */
@Data
@Entity
@Table(name = "environments")
public class Environment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;  // dev / test / staging / prod

    @Column(length = 100)
    private String displayName;  // 显示名称

    @Column(length = 500)
    private String description;

    @Column(length = 20)
    private String status = "ACTIVE";  // ACTIVE / INACTIVE

    @Column(length = 200)
    private String deployUrl;  // 部署目标 URL（模拟）

    @Column(length = 100)
    private String k8sNamespace;  // K8s 命名空间（模拟）

    @Column(nullable = false)
    private Boolean protectedEnv = false;  // 是否受保护环境（需要审批）

    // ==================== IaC 字段 (新增) ====================

    /** 关联项目 - 空表示全局环境 */
    @Column
    private Long projectId;

    /** K8s 集群名 (多集群场景) */
    @Column(length = 100)
    private String k8sCluster;

    /** CPU request (m) */
    @Column(length = 16)
    private String cpuRequest;

    /** CPU limit (m) */
    @Column(length = 16)
    private String cpuLimit;

    /** 内存 request (Mi/Gi) */
    @Column(length = 16)
    private String memoryRequest;

    /** 内存 limit (Mi/Gi) */
    @Column(length = 16)
    private String memoryLimit;

    /** 副本数 */
    @Column
    private Integer replicas = 1;

    /** Ingress 主机名 (e.g. apartment-dev.example.com) */
    @Column(length = 200)
    private String ingressHost;

    /** 模板来源 - 用了哪个 EnvironmentTemplate */
    @Column
    private Long templateId;

    /** 颜色标签 - 用于前端侧边栏区分 (e.g. #3B6D11 绿=dev, #A32D2D 红=prod) */
    @Column(length = 16)
    private String color = "#378ADD";

    /** 排序权重 - 数字越小越靠前 */
    @Column
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
