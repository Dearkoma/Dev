package com.devops.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Webhook 订阅 - 内部事件外发配置
 * <p>
 * 平台事件 (build.success / deploy.complete / security.failed) → POST 到外部 URL
 * 支持类型: 飞书 / 钉钉 / 企微 / 自定义 (Custom Webhook)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "webhook_subscriptions", indexes = {
        @Index(name = "idx_webhook_event", columnList = "eventType"),
        @Index(name = "idx_webhook_active", columnList = "active")
})
public class WebhookSubscription {

    /** 平台事件类型 */
    public static final String EVENT_BUILD_SUCCESS = "build.success";
    public static final String EVENT_BUILD_FAILED = "build.failed";
    public static final String EVENT_DEPLOY_SUCCESS = "deploy.success";
    public static final String EVENT_DEPLOY_FAILED = "deploy.failed";
    public static final String EVENT_SECURITY_FAILED = "security.failed";
    public static final String EVENT_INSTANCE_DOWN = "instance.down";

    /** Webhook 类型 */
    public static final String TYPE_FEISHU = "FEISHU";
    public static final String TYPE_DINGTALK = "DINGTALK";
    public static final String TYPE_WECOM = "WECOM";
    public static final String TYPE_CUSTOM = "CUSTOM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;  // 用户起的名字

    @Column(nullable = false, length = 32)
    private String type;  // FEISHU / DINGTALK / WECOM / CUSTOM

    @Column(nullable = false, length = 32)
    private String eventType;  // build.success / build.failed / ...

    @Column(nullable = false, length = 500)
    private String url;  // 接收 URL

    @Column(length = 500)
    private String secret;  // 签名密钥 (HMAC)

    /** 关联项目 (空 = 全局) */
    @Column
    private Long projectId;

    @Column(nullable = false)
    private Boolean active = true;

    /** 失败计数 (连续失败 5 次自动禁用) */
    @Column(nullable = false)
    private Integer failCount = 0;

    /** 最近一次触发时间 */
    private LocalDateTime lastTriggeredAt;

    /** 最近一次触发状态 (200 / 4xx / 5xx / ERROR) */
    @Column(length = 16)
    private String lastTriggerStatus;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
