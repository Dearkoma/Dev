package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * APM 性能指标时序记录
 * <p>
 * 一次 HTTP 请求一条记录,按 minute 聚合时用
 * 滚动窗口 (最近 1000 条) 计算 P50/P95/P99
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "http_metrics", indexes = {
        @Index(name = "idx_metric_path", columnList = "path"),
        @Index(name = "idx_metric_time", columnList = "recordedAt"),
        @Index(name = "idx_metric_status", columnList = "status")
})
public class HttpMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** HTTP 方法: GET / POST / PUT / DELETE */
    @Column(nullable = false, length = 8)
    private String method;

    /** 路由路径: /api/projects, /api/instances 等 */
    @Column(nullable = false, length = 256)
    private String path;

    /** HTTP 状态码: 200 / 404 / 500 */
    @Column(nullable = false)
    private Integer status;

    /** 响应耗时 (ms) */
    @Column(nullable = false)
    private Long durationMs;

    /** 调用方 IP */
    @Column(length = 64)
    private String clientIp;

    /** User-Agent 摘要 */
    @Column(length = 256)
    private String userAgent;

    /** 用户名 (已认证) */
    @Column(length = 64)
    private String username;

    @Column(nullable = false)
    private LocalDateTime recordedAt = LocalDateTime.now();
}
