package com.devops.platform.service;

import com.devops.platform.entity.WebhookSubscription;
import com.devops.platform.repository.WebhookSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Webhook 派发器
 * <p>
 * 内部事件 (build/deploy/security/instance) → 异步 POST 到所有订阅 URL
 * 支持飞书/钉钉/企微 (自动渲染为对应的消息格式) 和自定义 webhook (原样 POST)
 * 失败计数 5 次自动禁用, 防刷外部接口
 * <p>
 * 订阅匹配规则: 事件类型相同 + 已启用 + 项目匹配
 * (订阅绑定了 projectId 只收该项目事件; 未绑定视为全局订阅, 收全部项目事件)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcher {

    private final WebhookSubscriptionRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 派发事件到匹配的订阅端 (异步)
     *
     * @param eventType 事件类型 (build.success / deploy.failed / ...)
     * @param payload   消息体
     * @param projectId 事件所属项目; null 表示无项目上下文 (例如测试派发),
     *                  此时只命中「全局订阅」(projectId 为空那些)
     */
    @Async
    public void dispatch(String eventType, Map<String, Object> payload, Long projectId) {
        List<WebhookSubscription> subs = repository.findByEventTypeAndActiveTrue(eventType).stream()
                .filter(sub -> matchesProject(sub, projectId))
                .toList();
        if (subs.isEmpty()) {
            log.debug("[Webhook] 事件 {} 无匹配订阅 (projectId={})", eventType, projectId);
            return;
        }
        log.info("[Webhook] 事件 {} 匹配 {} 个订阅 (projectId={})", eventType, subs.size(), projectId);
        for (WebhookSubscription sub : subs) {
            deliver(sub, eventType, payload);
        }
    }

    /**
     * 只发给指定的一条订阅 (「测试」按钮专用)
     * 不走事件匹配, 避免点一条测试却把同事件的其他订阅全部触发
     */
    @Async
    public void dispatchTo(WebhookSubscription sub, String eventType, Map<String, Object> payload) {
        deliver(sub, eventType, payload);
    }

    /**
     * 订阅是否接收该项目的事件
     * - 订阅未绑定项目 → 全局订阅, 任何项目的事件都收
     * - 订阅绑定了项目 → 只收该项目的事件; 事件无项目上下文时不投递
     */
    private boolean matchesProject(WebhookSubscription sub, Long projectId) {
        Long bound = sub.getProjectId();
        if (bound == null) return true;
        return bound.equals(projectId);
    }

    /** 实际投递单条订阅: 组装消息体 → 签名 → POST → 记录结果 */
    private void deliver(WebhookSubscription sub, String eventType, Map<String, Object> payload) {
        if (sub == null) return;
        try {
            String body = buildBody(sub, eventType, payload);
            String signature = sign(sub.getSecret(), body);
            postJson(sub.getUrl(), body, signature, sub);
        } catch (Exception e) {
            log.warn("[Webhook] 派发失败 [{}] {}: {}", sub.getId(), sub.getName(), e.getMessage());
            recordFailure(sub);
        }
    }

    /**
     * 根据 webhook 类型构造消息体
     * 飞书/钉钉/企微: 套用官方机器人消息格式
     * CUSTOM: 原样 JSON
     */
    private String buildBody(WebhookSubscription sub, String eventType, Map<String, Object> payload) throws Exception {
        switch (sub.getType()) {
            case WebhookSubscription.TYPE_FEISHU:
                return objectMapper.writeValueAsString(Map.of(
                        "msg_type", "interactive",
                        "card", Map.of(
                                "header", Map.of(
                                        "title", Map.of("tag", "plain_text", "content", "[" + eventType + "] " + payload.getOrDefault("title", "D 平台通知")),
                                        "template", eventSeverityColor(eventType)
                                ),
                                "elements", List.of(
                                        Map.of("tag", "markdown", "content", formatMessage(eventType, payload))
                                )
                        )
                ));
            case WebhookSubscription.TYPE_DINGTALK:
                return objectMapper.writeValueAsString(Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of(
                                "title", "[" + eventType + "] " + payload.getOrDefault("title", "D 平台通知"),
                                "text", formatMessage(eventType, payload)
                        )
                ));
            case WebhookSubscription.TYPE_WECOM:
                return objectMapper.writeValueAsString(Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of(
                                "content", "## [" + eventType + "] " + payload.getOrDefault("title", "D 平台通知") +
                                        "\n\n" + formatMessage(eventType, payload)
                        )
                ));
            default:  // CUSTOM
                return objectMapper.writeValueAsString(Map.of(
                        "event", eventType,
                        "timestamp", LocalDateTime.now().toString(),
                        "data", payload
                ));
        }
    }

    private String formatMessage(String eventType, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            sb.append("**").append(e.getKey()).append("**: ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    private String eventSeverityColor(String eventType) {
        if (eventType.contains("failed") || eventType.contains("down")) return "red";
        if (eventType.contains("success") || eventType.contains("complete")) return "green";
        return "blue";
    }

    private void postJson(String url, String body, String signature, WebhookSubscription sub) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-DevOps-Event", "platform-notification");
        if (signature != null) req.header("X-DevOps-Signature", "sha256=" + signature);
        HttpRequest request = req.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        sub.setLastTriggeredAt(LocalDateTime.now());
        sub.setLastTriggerStatus(String.valueOf(code));
        if (code >= 200 && code < 300) {
            sub.setFailCount(0);
            log.info("[Webhook] 发送成功 [{}] {} → {}", sub.getId(), sub.getName(), code);
        } else {
            log.warn("[Webhook] 发送失败 [{}] {} → HTTP {}", sub.getId(), sub.getName(), code);
            recordFailure(sub);
        }
        repository.save(sub);
    }

    private void recordFailure(WebhookSubscription sub) {
        sub.setFailCount(sub.getFailCount() + 1);
        if (sub.getFailCount() >= 5 && Boolean.TRUE.equals(sub.getActive())) {
            sub.setActive(false);
            log.warn("[Webhook] 连续失败 5 次, 自动禁用 [{}] {}", sub.getId(), sub.getName());
        }
        repository.save(sub);
    }

    /**
     * HMAC-SHA256 签名
     * 接收端用同一个 secret 算 signature 比对 → 验证来源
     */
    private String sign(String secret, String body) {
        if (secret == null || secret.isBlank()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            return null;
        }
    }
}
