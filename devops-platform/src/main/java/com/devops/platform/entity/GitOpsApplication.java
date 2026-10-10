package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * GitOps Application — ArgoCD 风格的应用声明
 * 定义 Git 仓库中的 K8s 资源配置与目标集群的同步关系
 */
@Data
@Entity
@Table(name = "gitops_applications")
public class GitOpsApplication {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Long projectId;

    @Column(length = 500)
    private String gitRepoUrl;        // Git 仓库 URL

    @Column(length = 200)
    private String gitPath = ".";     // K8s YAML 路径（相对仓库根）

    @Column(length = 100)
    private String gitBranch = "main";

    @Column(length = 200)
    private String targetCluster;     // 目标集群名称

    @Column(length = 100)
    private String targetNamespace;   // 目标命名空间

    @Column(length = 20)
    private String syncPolicy = "MANUAL";  // MANUAL / AUTO_SYNC / AUTO_PRUNE

    @Column(length = 20)
    private String syncStatus = "UNKNOWN"; // SYNCED / OUT_OF_SYNC / UNKNOWN

    @Column(length = 20)
    private String healthStatus = "UNKNOWN"; // HEALTHY / DEGRADED / PROGRESSING

    @Column
    private Long clusterConfigId;     // 关联 ClusterConfig

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime lastSyncAt;

    @Column(length = 100)
    private String lastSyncCommit;
}
