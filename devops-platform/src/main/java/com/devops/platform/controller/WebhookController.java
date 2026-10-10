package com.devops.platform.controller;

import com.devops.platform.entity.Build;
import com.devops.platform.service.BuildService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * 入向 Webhook — 接收 Git 平台 push 事件触发构建
 *
 * 签名校验（防止伪造 push 事件）:
 *  - GitHub: X-Hub-Signature-256: sha256=<HMAC(body)>
 *  - GitLab: X-Gitlab-Token: <token>
 *  - Gitee:  X-Gitee-Token: <token>
 *  - 通用:   X-DevOps-Signature: sha256=<HMAC(body)>
 *
 * 共享密钥配置: devops.webhook.inbound-secret（未配置时跳过校验，向后兼容）
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final BuildService buildService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${devops.webhook.inbound-secret:}")
    private String inboundSecret;

    /** GitHub Webhook */
    @PostMapping("/github/{projectId}")
    public ResponseEntity<?> githubWebhook(
            @PathVariable Long projectId,
            @RequestBody String rawBody,
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "push") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        if (!verifyHmac(rawBody, signature)) return unauthorized();
        return handleWebhook(projectId, "GitHub", event, parseBody(rawBody));
    }

    /** GitLab Webhook */
    @PostMapping("/gitlab/{projectId}")
    public ResponseEntity<?> gitlabWebhook(
            @PathVariable Long projectId,
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "Push Hook") String event,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token) {
        if (!verifyToken(token)) return unauthorized();
        return handleWebhook(projectId, "GitLab", event, parseBody(rawBody));
    }

    /** Gitee Webhook */
    @PostMapping("/gitee/{projectId}")
    public ResponseEntity<?> giteeWebhook(
            @PathVariable Long projectId,
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Gitee-Token", required = false) String token) {
        if (!verifyToken(token)) return unauthorized();
        return handleWebhook(projectId, "Gitee", "Push Hook", parseBody(rawBody));
    }

    /** 通用 Webhook */
    @PostMapping("/{projectId}")
    public ResponseEntity<?> genericWebhook(
            @PathVariable Long projectId,
            @RequestBody String rawBody,
            @RequestHeader(value = "X-DevOps-Signature", required = false) String signature) {
        if (!verifyHmac(rawBody, signature)) return unauthorized();
        return handleWebhook(projectId, "Generic", "push", parseBody(rawBody));
    }

    // ==================== 签名校验 ====================

    private boolean verifyHmac(String rawBody, String expectedSignature) {
        if (inboundSecret == null || inboundSecret.isBlank()) return true;  // 未配置密钥则跳过
        if (expectedSignature == null || expectedSignature.isBlank()) {
            log.warn("[Webhook] 缺少签名头, 拒绝");
            return false;
        }
        String expected = expectedSignature.startsWith("sha256=")
                ? expectedSignature.substring(7) : expectedSignature;
        String computed = hmacSha256(rawBody == null ? "" : rawBody, inboundSecret);
        return constantTimeEquals(computed, expected);
    }

    private boolean verifyToken(String token) {
        if (inboundSecret == null || inboundSecret.isBlank()) return true;
        if (token == null || token.isBlank()) {
            log.warn("[Webhook] 缺少 token 头, 拒绝");
            return false;
        }
        return constantTimeEquals(token, inboundSecret);
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("[Webhook] HMAC 计算失败", e);
            return "";
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            log.warn("[Webhook] 解析 payload 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of(
                "success", false,
                "error", "Webhook 签名校验失败"
        ));
    }

    // ==================== 事件处理 ====================

    @SuppressWarnings("unchecked")
    private ResponseEntity<?> handleWebhook(Long projectId, String source, String event, Map<String, Object> payload) {
        try {
            String branch = extractBranch(source, payload);
            String commit = extractCommit(source, payload);
            String committer = extractCommitter(source, payload);

            log.info("收到 {} Webhook: project={}, branch={}, commit={}", source, projectId, branch, commit);

            // 查找项目下匹配分支的活跃流水线
            Build build = buildService.triggerBuildByWebhook(
                    projectId, branch, committer, commit);

            log.info("Webhook 触发构建成功: build={}", build.getBuildNumber());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "source", source,
                    "buildId", build.getId(),
                    "buildNumber", build.getBuildNumber(),
                    "message", "构建已触发: " + build.getBuildNumber()
            ));
        } catch (Exception e) {
            log.error("Webhook 处理失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    private String extractBranch(String source, Map<String, Object> payload) {
        try {
            if ("GitHub".equals(source)) {
                String ref = (String) payload.getOrDefault("ref", "");
                return ref.replace("refs/heads/", "");
            } else if ("GitLab".equals(source)) {
                String ref = (String) payload.getOrDefault("ref", "");
                return ref.replace("refs/heads/", "");
            } else if ("Gitee".equals(source)) {
                String ref = (String) payload.getOrDefault("ref", "");
                return ref.replace("refs/heads/", "");
            }
        } catch (Exception ignored) {}
        return "main";
    }

    @SuppressWarnings("unchecked")
    private String extractCommit(String source, Map<String, Object> payload) {
        try {
            if ("GitHub".equals(source)) {
                Map<String, Object> head = (Map<String, Object>) payload.get("head_commit");
                if (head != null) return (String) head.getOrDefault("id", "");
                return (String) payload.getOrDefault("after", "");
            } else if ("GitLab".equals(source)) {
                Map<String, Object> commits = (Map<String, Object>) payload.get("commits");
                if (commits != null) {
                    java.util.List<Map<String, Object>> list = (java.util.List<Map<String, Object>>) commits.get("object");
                    return "";
                }
                return (String) payload.getOrDefault("checkout_sha", "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractCommitter(String source, Map<String, Object> payload) {
        try {
            if ("GitHub".equals(source)) {
                Map<String, Object> head = (Map<String, Object>) payload.get("head_commit");
                if (head != null) {
                    Map<String, Object> author = (Map<String, Object>) head.get("author");
                    if (author != null) return (String) author.getOrDefault("name", "webhook");
                }
                Map<String, Object> pusher = (Map<String, Object>) payload.get("pusher");
                if (pusher != null) return (String) pusher.getOrDefault("name", "webhook");
            } else if ("GitLab".equals(source)) {
                return (String) payload.getOrDefault("user_username", "webhook");
            } else if ("Gitee".equals(source)) {
                Map<String, Object> sender = (Map<String, Object>) payload.get("sender");
                if (sender != null) return (String) sender.getOrDefault("name", "webhook");
            }
        } catch (Exception ignored) {}
        return "webhook";
    }
}
