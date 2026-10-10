package com.devops.platform.controller;

import com.devops.platform.entity.DeploymentHistory;
import com.devops.platform.entity.DeploymentRequest;
import com.devops.platform.service.DeploymentService;
import com.devops.platform.service.NotificationService;
import com.devops.platform.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/deployments")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final NotificationService notificationService;
    private final ProjectAccessService accessService;

    /** 申请部署 */
    @PostMapping("/request")
    public ResponseEntity<DeploymentRequest> requestDeploy(@RequestBody Map<String, Object> body, Principal principal) {
        Long buildId = Long.valueOf(body.get("buildId").toString());
        Long environmentId = Long.valueOf(body.get("environmentId").toString());
        // 对象级授权: 只能对自己有权限的项目发起部署
        accessService.assertCanAccessBuild(buildId);
        // 优先使用 JWT 中的当前用户, 防止前端伪造 requestedBy
        String requestedBy = principal != null ? principal.getName()
                : (String) body.getOrDefault("requestedBy", "unknown");
        String reason = (String) body.getOrDefault("reason", "");
        String deployStrategy = (String) body.getOrDefault("deployStrategy", "ROLLING");

        DeploymentRequest req = deploymentService.requestDeploy(buildId, environmentId, requestedBy, reason, deployStrategy);
        if ("PENDING".equals(req.getStatus())) {
            notificationService.deployApprovalRequest(req.getId(), req.getProjectName(), req.getEnvironmentName());
        }
        return ResponseEntity.ok(req);
    }

    /** 审批通过 */
    @PostMapping("/approve/{id}")
    public ResponseEntity<?> approve(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        // 审批人必须从 JWT 取, 不允许前端伪造
        String approvedBy = principal != null ? principal.getName() : "unknown";
        try {
            DeploymentRequest req = deploymentService.approve(id, approvedBy);
            notifyResult(req, true, null);
            return ResponseEntity.ok(Map.of("success", true, "request", req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 审批拒绝 */
    @PostMapping("/reject/{id}")
    public ResponseEntity<?> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        String approvedBy = principal != null ? principal.getName() : "unknown";
        String reason = body != null ? body.getOrDefault("reason", "无") : "无";
        try {
            DeploymentRequest req = deploymentService.reject(id, approvedBy, reason);
            notifyResult(req, false, reason);
            return ResponseEntity.ok(Map.of("success", true, "request", req));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 通知申请人审批结果。
     * 通知只是旁路能力: 即便发送失败也不能影响审批本身已生效的结果, 故单独 try/catch 吞掉。
     */
    private void notifyResult(DeploymentRequest req, boolean approved, String reason) {
        try {
            notificationService.deployApprovalResult(req.getId(), req.getProjectName(),
                    req.getEnvironmentName(), req.getRequestedBy(), approved, reason);
        } catch (Exception ex) {
            log.warn("发送部署审批结果通知失败 requestId={}: {}", req.getId(), ex.getMessage());
        }
    }

    /** 获取待审批列表 */
    @GetMapping("/pending")
    public List<DeploymentRequest> getPending() {
        return accessService.filterByAccessibleProjects(deploymentService.getPendingRequests(), DeploymentRequest::getProjectId);
    }

    /** 获取项目的部署申请 */
    @GetMapping("/requests")
    public List<DeploymentRequest> getRequests(@RequestParam(required = false) Long projectId) {
        if (projectId != null) {
            accessService.assertCanAccessProject(projectId);
            return deploymentService.getRequestsByProject(projectId);
        }
        return accessService.filterByAccessibleProjects(deploymentService.getRequestsByProject(null), DeploymentRequest::getProjectId);
    }

    /** 获取部署历史 */
    @GetMapping("/history")
    public List<DeploymentHistory> getHistory(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long environmentId) {
        if (projectId != null) {
            accessService.assertCanAccessProject(projectId);
            return deploymentService.getHistory(projectId, environmentId);
        }
        return accessService.filterByAccessibleProjects(deploymentService.getHistory(null, environmentId), DeploymentHistory::getProjectId);
    }

    /** 获取可回滚的部署点 */
    @GetMapping("/rollback-candidates")
    public List<DeploymentHistory> getRollbackCandidates(
            @RequestParam Long projectId,
            @RequestParam Long environmentId) {
        accessService.assertCanAccessProject(projectId);
        return deploymentService.getRollbackCandidates(projectId, environmentId);
    }

    /** 执行回滚 */
    @PostMapping("/rollback/{historyId}")
    public ResponseEntity<?> rollback(
            @PathVariable Long historyId,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        String triggeredBy = principal != null ? principal.getName() : "unknown";
        try {
            DeploymentHistory result = deploymentService.rollback(historyId, triggeredBy);
            return ResponseEntity.ok(Map.of("success", true, "rollback", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除部署历史 */
    @DeleteMapping("/history/{id}")
    public ResponseEntity<?> deleteHistory(@PathVariable Long id, Principal principal) {
        try {
            String operator = principal != null ? principal.getName() : "unknown";
            deploymentService.deleteDeploymentHistory(id, operator);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除部署申请（仅允许 REJECTED/DEPLOYED 状态） */
    @DeleteMapping("/request/{id}")
    public ResponseEntity<?> deleteRequest(@PathVariable Long id, Principal principal) {
        try {
            String operator = principal != null ? principal.getName() : "unknown";
            deploymentService.deleteDeploymentRequest(id, operator);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
