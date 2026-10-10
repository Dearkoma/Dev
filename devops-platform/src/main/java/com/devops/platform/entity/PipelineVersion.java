package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 流水线版本历史
 * 每次修改流水线定义时自动保存一份快照
 */
@Data
@Entity
@Table(name = "pipeline_versions")
public class PipelineVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long pipelineId;

    @Column(nullable = false)
    private Integer versionNumber;  // 递增版本号: 1, 2, 3...

    @Column(columnDefinition = "TEXT")
    private String definition;  // 该版本的完整 JSON 定义

    @Column(length = 50)
    private String changedBy;  // 修改人

    @Column(length = 500)
    private String changeNote;  // 变更说明

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
