package com.devops.platform.controller;

import com.devops.platform.entity.WebhookSubscription;
import com.devops.platform.repository.WebhookSubscriptionRepository;
import com.devops.platform.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Webhook 订阅管理 - 内部事件 → 外部 IM / 通知
 * (与 /api/webhook/github/{id} 接收方向相反)
 *  - GET    /api/webhooks/subscriptions          全部订阅
 *  - GET    /api/webhooks/subscriptions/active  启用的
 *  - POST   /api/webhooks/subscriptions          新建
 *  - PUT    /api/webhooks/subscriptions/{id}     更新
 *  - DELETE /api/webhooks/subscriptions/{id}     删除
 *  - POST   /api/webhooks/subscriptions/{id}/test  测试发送
 *  - POST   /api/webhooks/subscriptions/{id}/toggle 启用/禁用
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookSubscriptionController {

    private final WebhookSubscriptionRepository repository;
    private final WebhookDispatcher dispatcher;

    @GetMapping("/subscriptions")
    public List<WebhookSubscription> all() {
        return repository.findAll();
    }

    @GetMapping("/subscriptions/active")
    public List<WebhookSubscription> active() {
        return repository.findByActiveTrue();
    }

    @PostMapping("/subscriptions")
    public WebhookSubscription create(@RequestBody WebhookSubscription sub) {
        sub.setFailCount(0);
        return repository.save(sub);
    }

    @PutMapping("/subscriptions/{id}")
    public ResponseEntity<WebhookSubscription> update(@PathVariable Long id, @RequestBody WebhookSubscription body) {
        return repository.findById(id)
                .map(s -> {
                    s.setName(body.getName());
                    s.setType(body.getType());
                    s.setEventType(body.getEventType());
                    s.setUrl(body.getUrl());
                    s.setSecret(body.getSecret());
                    s.setProjectId(body.getProjectId());
                    s.setActive(body.getActive() != null ? body.getActive() : true);
                    return ResponseEntity.ok(repository.save(s));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/subscriptions/{id}/toggle")
    public ResponseEntity<WebhookSubscription> toggle(@PathVariable Long id) {
        return repository.findById(id)
                .map(s -> {
                    s.setActive(!Boolean.TRUE.equals(s.getActive()));
                    s.setFailCount(0);  // 重新启用清零失败计数
                    return ResponseEntity.ok(repository.save(s));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/subscriptions/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id) {
        WebhookSubscription sub = repository.findById(id).orElse(null);
        if (sub == null) return ResponseEntity.notFound().build();
        Map<String, Object> payload = Map.of(
                "title", "D 平台 Webhook 测试消息",
                "message", "这是一条测试消息, 如果你收到说明配置正确",
                "projectId", sub.getProjectId() == null ? "" : sub.getProjectId().toString(),
                "webhookId", String.valueOf(sub.getId()),
                "timestamp", java.time.LocalDateTime.now().toString()
        );
        // 只发给当前这一条订阅, 避免误发给同事件的其他订阅
        dispatcher.dispatchTo(sub, sub.getEventType(), payload);
        return ResponseEntity.ok(Map.of(
                "dispatched", true,
                "eventType", sub.getEventType(),
                "message", "已向该订阅发送测试消息 (异步), 请检查接收端"
        ));
    }
}
