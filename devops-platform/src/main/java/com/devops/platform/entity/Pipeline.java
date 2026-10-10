package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 流水线实体
 * 定义 CI/CD 流水线的阶段和步骤
 */
@Data
@Entity
@Table(name = "pipelines")
public class Pipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /**
     * 流水线定义（JSON 格式）
     * 包含多个阶段(stage)，每个阶段包含多个步骤(step)
     * 示例：
     * [
     *   {
     *     "name": "编译构建",
     *     "parallel": false,
     *     "steps": [
     *       {"name":"Maven编译","type":"MVN_PACKAGE","command":"mvn clean package","retry":2,"timeout":300}
     *     ]
     *   },
     *   {
     *     "name": "安全检查",
     *     "parallel": true,
     *     "steps": [
     *       {"name":"Trivy FS","type":"SECURITY_SCAN_FS","command":"thresholdHigh=5","timeout":180},
     *       {"name":"Trivy 依赖","type":"SECURITY_SCAN_DEP","command":"thresholdHigh=10","timeout":180}
     *     ]
     *   }
     * ]
     *
     * 字段说明:
     *   stage.parallel: 阶段内步骤是否并行执行（默认 false）
     *   step.retry: 失败重试次数（0=不重试，2=最多重试2次即总共3次）
     *   step.timeout: 步骤超时秒数（0=使用全局600s超时）
     */
    @Column(columnDefinition = "TEXT")
    private String definition;  // JSON

    @Column(length = 20)
    private String status = "ACTIVE";  // ACTIVE / INACTIVE

    @Column(length = 100)
    private String cronExpression;  // Cron 表达式，用于定时构建

    @Column(length = 200)
    private String branchPattern;  // 分支匹配模式，如 "feature/*" 或 "main,develop"

    @Column(nullable = false)
    private Boolean cronEnabled = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
