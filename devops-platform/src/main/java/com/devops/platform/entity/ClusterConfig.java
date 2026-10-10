package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 多集群配置 — 支持管理多个 K8s 集群
 * kubeconfig 使用 AES-256-GCM 加密存储
 */
@Data
@Entity
@Table(name = "cluster_configs")
public class ClusterConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;           // 集群名称

    @Column(length = 20)
    private String provider = "K8S";  // K8S / EKS / AKS / GKE / K3S

    @Column(length = 500)
    private String endpoint;       // API Server 地址

    @Column(columnDefinition = "TEXT")
    private String kubeconfigEncrypted;  // 加密的 kubeconfig

    @Column
    private Boolean isDefault = false;

    @Column
    private Boolean enabled = true;

    @Column(length = 100)
    private String region;         // 区域

    @Column
    private Integer nodeCount;

    @Column
    private Double cpuCores;

    @Column
    private Double memoryGb;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
