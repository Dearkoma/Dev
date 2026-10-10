package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 构建变量（项目级或流水线级环境变量）
 * secret=true 时 value 存储 AES-256-GCM 加密后的密文
 */
@Data
@Entity
@Table(name = "build_variables")
public class BuildVariable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 项目级变量（项目内所有流水线共享） */
    @Column
    private Long projectId;

    /** 流水线级变量（仅该流水线可用，优先级高于项目级） */
    @Column
    private Long pipelineId;

    @Column(nullable = false, length = 100)
    private String varKey;  // 变量名，如 DATABASE_URL

    @Column(columnDefinition = "TEXT")
    private String value;  // 变量值（secret=true时为密文）

    @Column(nullable = false)
    private Boolean secret = false;  // 是否为密钥（加密存储 + 日志脱敏）

    @Column(length = 200)
    private String description;  // 变量说明

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
