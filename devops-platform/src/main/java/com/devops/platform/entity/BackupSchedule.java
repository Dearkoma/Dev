package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 数据库自动备份调度
 */
@Data
@Entity
@Table(name = "backup_schedules")
public class BackupSchedule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String backupType = "MYSQL";    // MYSQL / POSTGRES / VOLUME / CONFIG

    @Column(nullable = false)
    private Long projectId;

    @Column(length = 200)
    private String sourceDbName;            // 源数据库名

    @Column(length = 500)
    private String backupPath;              // 备份存储路径

    @Column(length = 30)
    private String cronExpression = "0 2 * * *";  // 默认每天凌晨 2 点

    @Column
    private Integer retentionDays = 30;     // 备份保留天数

    @Column
    private Boolean enabled = true;

    @Column(length = 20)
    private String status = "IDLE";         // IDLE / RUNNING / SUCCESS / FAILED

    @Column
    private LocalDateTime lastBackupAt;

    @Column(length = 200)
    private String lastBackupFile;

    @Column
    private Long lastBackupSizeBytes;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
