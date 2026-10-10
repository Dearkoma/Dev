package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 混沌工程实验 — 测试系统韧性
 * 支持: Pod Kill / Network Delay / CPU Stress / Memory Stress
 */
@Data
@Entity
@Table(name = "chaos_experiments")
public class ChaosExperiment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    private String experimentType;  // POD_KILL / NETWORK_DELAY / CPU_STRESS / MEMORY_STRESS / IO_STRESS

    @Column(length = 200)
    private String targetSelector;  // app=my-app,namespace=devops

    @Column
    private Integer durationSeconds = 60;

    @Column(length = 20)
    private String status = "PENDING";  // PENDING / RUNNING / COMPLETED / ABORTED

    @Column(columnDefinition = "TEXT")
    private String parameters;     // JSON: {"delay":"500ms","jitter":"100ms"}

    @Column(columnDefinition = "TEXT")
    private String result;         // JSON: 实验结果摘要

    @Column
    private Long projectId;

    @Column(length = 50)
    private String triggeredBy;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime completedAt;
}
