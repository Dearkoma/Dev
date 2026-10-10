package com.devops.platform.service;

import com.devops.platform.config.EventWebSocketHandler;
import com.devops.platform.entity.Notification;
import com.devops.platform.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /** 发送通知 */
    public Notification send(String type, String title, String message, String recipient, Long relatedId, String relatedType) {
        Notification n = new Notification();
        n.setType(type);
        n.setTitle(title);
        n.setContent(message);
        n.setMessage(message);
        n.setRecipient(recipient);
        n.setRelatedId(relatedId);
        n.setRelatedType(relatedType);
        Notification saved = notificationRepository.save(n);
        // WebSocket 广播, 前端 NotificationCenter 收到后立即刷新, 替代 15s 轮询
        try {
            EventWebSocketHandler.broadcast("notification.created", Map.of(
                    "id", saved.getId(),
                    "type", saved.getType(),
                    "title", saved.getTitle() == null ? "" : saved.getTitle(),
                    "recipient", saved.getRecipient() == null ? "" : saved.getRecipient()
            ));
        } catch (Exception e) {
            log.warn("推送通知 WS 事件失败: {}", e.getMessage());
        }
        return saved;
    }

    /** 构建成功通知 */
    public void buildSuccess(String buildNumber, Long buildId, String projectName) {
        send("BUILD_SUCCESS",
                "构建成功: " + buildNumber,
                "项目 " + projectName + " 的构建 " + buildNumber + " 已成功完成",
                null, buildId, "BUILD");
    }

    /** 构建失败通知 */
    public void buildFailed(String buildNumber, Long buildId, String projectName, String error) {
        send("BUILD_FAILED",
                "构建失败: " + buildNumber,
                "项目 " + projectName + " 的构建 " + buildNumber + " 失败: " + error,
                null, buildId, "BUILD");
    }

    /** 部署审批通知 */
    public void deployApprovalRequest(Long requestId, String projectName, String envName) {
        send("DEPLOY_APPROVAL",
                "部署审批: " + projectName,
                "项目 " + projectName + " 申请部署到 " + envName + " 环境，等待审批",
                null, requestId, "DEPLOYMENT");
    }

    /**
     * 部署审批结果通知 — 定向发给申请人本人。
     * 与 deployApprovalRequest(recipient=null, 全站广播给审批人) 相对，
     * 这条只发给申请人，让申请人能收到"通过/被拒"的反馈。
     */
    public void deployApprovalResult(Long requestId, String projectName, String envName,
                                     String requestedBy, boolean approved, String reason) {
        if (requestedBy == null || requestedBy.isBlank()) return;  // 无明确申请人则不发送
        String title = approved
                ? "部署审批通过: " + projectName
                : "部署审批被拒绝: " + projectName;
        String message = approved
                ? "你申请的项目 " + projectName + " 部署到 " + envName + " 环境已通过审批，正在执行部署"
                : "你申请的项目 " + projectName + " 部署到 " + envName + " 环境已被拒绝"
                  + (reason != null && !reason.isBlank() ? "，原因: " + reason : "");
        send(approved ? "DEPLOY_APPROVED" : "DEPLOY_REJECTED",
                title, message, requestedBy, requestId, "DEPLOYMENT");
    }

    public List<Notification> getUserNotifications(String username) {
        return notificationRepository.findByRecipientOrRecipientIsNullOrderByCreatedAtDesc(username);
    }

    public Long getUnreadCount(String username) {
        Long personal = notificationRepository.countByRecipientAndIsReadFalse(username);
        return personal + notificationRepository.countByIsReadFalse();
    }

    public void markAsRead(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    public void markAllAsRead(String username) {
        List<Notification> all = notificationRepository.findByRecipientOrRecipientIsNullOrderByCreatedAtDesc(username);
        all.forEach(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    /** 删除单条通知 */
    public void deleteNotification(Long id) {
        notificationRepository.deleteById(id);
        log.info("已删除通知 #{}", id);
    }

    /** 清空全部通知 */
    public int deleteAllNotifications(String username) {
        List<Notification> all = notificationRepository
                .findByRecipientOrRecipientIsNullOrderByCreatedAtDesc(username);
        notificationRepository.deleteAll(all);
        log.info("已清空 {} 条通知", all.size());
        return all.size();
    }
}
