package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 成本记录 — 按项目/环境/资源类型追踪云资源成本
 */
@Data
@Entity
@Table(name = "cost_records")
public class CostRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(length = 50)
    private String environment;       // dev / staging / prod

    @Column(length = 30)
    private String resourceType;      // COMPUTE / STORAGE / NETWORK / DATABASE / OTHER

    @Column(length = 200)
    private String resourceName;      // 资源标识

    @Column
    private Double costAmount;        // 金额

    @Column(length = 10)
    private String currency = "CNY";

    @Column(nullable = false)
    private LocalDate billingDate;    // 账单日期

    @Column(length = 20)
    private String billingPeriod;     // HOURLY / DAILY / MONTHLY

    @Column
    private Double cpuHours;

    @Column
    private Double memoryGbHours;

    @Column
    private Double storageGb;

    @Column
    private Double networkGb;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime recordedAt;
}
