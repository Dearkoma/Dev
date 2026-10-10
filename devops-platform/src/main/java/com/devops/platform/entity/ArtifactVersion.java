package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 制品版本 - 一个项目下同一类制品的多个版本
 * <p>
 * 一个 artifact (e.g. apartment JAR) 多次构建产生多个 version:
 *  - v1.0.0 / v1.0.1 / v1.1.0 ...
 *  - 每次构建的 buildId + sha256 + size + 保留策略标记
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "artifact_versions", indexes = {
        @Index(name = "idx_ver_project", columnList = "projectId"),
        @Index(name = "idx_ver_artifact", columnList = "artifactId"),
        @Index(name = "idx_ver_build", columnList = "buildId"),
        @Index(name = "idx_ver_status", columnList = "status")
})
public class ArtifactVersion {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEPRECATED = "DEPRECATED";
    public static final String STATUS_RETAINED = "RETAINED";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long buildId;

    /** 关联 Artifact 主记录 */
    @Column(nullable = false)
    private Long artifactId;

    /** 版本号 (semver) e.g. 1.2.3 或构建号 #42 */
    @Column(nullable = false, length = 100)
    private String version;

    /** SHA256 校验和 - 制品完整性 */
    @Column(length = 64)
    private String sha256;

    /** 文件大小 (bytes) */
    @Column
    private Long size;

    /** 存储路径/URL */
    @Column(length = 500)
    private String storagePath;

    /** 制品元数据 (JSON 字符串: dockerImage/buildTime/gitCommit/...) */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /** 签名 (可选) */
    @Column(length = 256)
    private String signature;

    /** 状态: ACTIVE / DEPRECATED / RETAINED / DELETED */
    @Column(nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    /** 部署次数 (用了几次) */
    @Column(nullable = false)
    private Integer deployCount = 0;

    /** 最后部署时间 */
    private LocalDateTime lastDeployedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
