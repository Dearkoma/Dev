package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 项目成员 — 项目级权限
 * <p>
 * 角色:
 *   OWNER     — 项目负责人 (审批部署、管理成员、编辑项目)
 *   MAINTAINER — 维护者 (审批部署、触发构建)
 *   DEVELOPER — 开发者 (触发构建、申请部署)
 *   VIEWER    — 观察者 (只读)
 */
@Data
@Entity
@Table(name = "project_members", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"project_id", "username"})
})
public class ProjectMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 20)
    private String role = "DEVELOPER";  // OWNER / MAINTAINER / DEVELOPER / VIEWER

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
