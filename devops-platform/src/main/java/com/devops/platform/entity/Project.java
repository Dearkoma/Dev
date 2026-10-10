package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 项目实体
 */
@Data
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50, unique = true)
    private String code;  // 项目编码，用于路径/标识

    @Column(length = 500)
    private String description;

    @Column(length = 500)
    private String gitUrl;

    /** Git 分支; 留空 = 跟随远端默认分支 (main / master 自动识别) */
    @Column(length = 100)
    private String gitBranch = "";

    /**
     * Git 平台访问令牌 (PAT)。
     * AES-GCM 加密后存储, 读取时解密; 用于调用 GitHub Actions / Release API。
     * 注意: 该字段不由 updateProject 同步(避免前端整体提交表单时把凭证清空),
     * 只通过 PUT /api/git/{id}/config 维护。
     */
    @Column(length = 1000)
    private String gitToken;

    /** GitHub Actions workflow 文件名 (如 build.yaml), 用于触发云端构建与发布 */
    @Column(length = 200)
    private String githubWorkflow = "build.yaml";

    @Column(length = 200)
    private String buildCommand;

    @Column(length = 200)
    private String startCommand;

    @Column(length = 500)
    private String dockerfileContent;  // D 平台为 O 项目托管的 Dockerfile 内容（O 项目仓库无 Dockerfile 时使用）

    @Column(length = 200)
    private String dockerfilePath = "Dockerfile";  // Dockerfile 路径（默认 Dockerfile，可改为 subdir/Dockerfile）

    @Column(length = 50)
    private String language = "Java";  // Java / Node.js / Python / Go

    @Column(length = 50)
    private String framework;  // Spring Boot / Express / Django / Gin

    @Column(length = 500)
    private String capabilities;  // 勾选的构建能力(逗号分隔), 如 "BUILD,TEST,SAST,DOCKER" — 决定默认流水线包含哪些阶段

    @Column(length = 20)
    private String dbType = "H2";  // H2 / MYSQL — O 项目数据库类型

    @Column(length = 200)
    private String dbHost;  // MySQL 主机地址

    @Column
    private Integer dbPort = 3306;  // MySQL 端口

    @Column(length = 100)
    private String dbUsername;  // MySQL 用户名

    @Column(length = 200)
    private String dbPassword;  // MySQL 密码

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 20)
    private String status = "IDLE";  // IDLE / BUILDING / DEPLOYED / FAILED

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column
    private Long ownerId;  // 项目负责人
}
