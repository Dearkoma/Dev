package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Feature Flag / 特性开关 — 灰度发布和功能切换
 */
@Data
@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 列名用 flag_key：key 是 MySQL 保留字，直接作列名会导致建表 SQL 语法错误
    @Column(name = "flag_key", nullable = false, unique = true, length = 100)
    private String key;            // 开关标识，如 "new-checkout-flow"

    @Column(length = 200)
    private String name;           // 显示名称

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 20)
    private String flagType = "BOOLEAN";  // BOOLEAN / PERCENTAGE / TARGETED

    @Column
    private Boolean enabled = false;

    @Column
    private Integer rolloutPercent = 0;  // 0-100 灰度百分比

    @Column(length = 500)
    private String targetUsers;    // 定向用户列表（JSON 数组）

    @Column(length = 500)
    private String rules;          // 自定义规则（JSON）

    @Column(nullable = false)
    private Long projectId;

    @Column(length = 50)
    private String environment;    // dev / staging / prod

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;
}
