package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * SLSA 供应链安全 — 构建来源证明 (Provenance)
 * 记录谁、用什么代码、在什么环境、经过哪些步骤构建了制品
 */
@Data
@Entity
@Table(name = "slsa_provenances")
public class SlsaProvenance {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long buildId;

    @Column
    private Long projectId;

    @Column(length = 100)
    private String slsaLevel = "L2";       // L1 / L2 / L3

    @Column(length = 128)
    private String artifactDigest;         // SHA256:xxx

    @Column(length = 500)
    private String artifactUri;            // 制品存储位置

    @Column(columnDefinition = "MEDIUMTEXT")
    private String buildDefinition;        // 构建定义 JSON（完整流水线快照）

    @Column(columnDefinition = "TEXT")
    private String materials;              // 输入材料: 源码 repo + commit + deps

    @Column(length = 500)
    private String builderId;              // 构建器身份

    @Column
    private Boolean verified = false;

    @Column(columnDefinition = "TEXT")
    private String inTotoStatement;        // in-toto 声明 JSON (SLSA v1.0 格式)

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
