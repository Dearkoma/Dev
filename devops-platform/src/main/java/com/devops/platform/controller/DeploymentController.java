package com.devops.platform.controller;

import com.devops.platform.entity.DeploymentHistory;
import com.devops.platform.entity.DeploymentRequest;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import com.devops.platform.service.DeploymentService;
import com.devops.platform.service.DeploymentStrategyService;
import com.devops.platform.service.NotificationService;
import com.devops.platform.service.ProjectAccessService;
import com.devops.platform.service.ProjectMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final DeploymentStrategyService strategyService;
    private final ProjectRepository projectRepository;
    private final ProjectMemberService memberService;

    /** 与 BuildService 的部署命名空间保持一致 */
    @Value("${devops.pipeline.k8s.namespace:default}")
    private String k8sNamespace;

    // ==================== 金丝雀发布闭环 ====================

    /** 查询项目当前金丝雀状态（查看无需特殊权限，能访问项目即可） */
    @GetMapping("/canary-status")
    public ResponseEntity<?> canaryStatus(@RequestParam Long projectId) {
        accessService.assertCanAccessProject(projectId);
        Project p = projectRepository.findById(projectId).orElse(null);
        if (p == null || p.getCode() == null) {
            return ResponseEntity.ok(Map.of("exists", false));
        }
        return ResponseEntity.ok(strategyService.canaryStatus(k8sNamespace, p.getCode()));
    }

    /** 金丝雀全量提升：canary 镜像覆盖 stable，稳定后删除 canary（权限同部署审批） */
    @PostMapping("/canary-promote")
    public ResponseEntity<?> promoteCanary(@RequestParam Long projectId) {
        ResponseEntity<?> denied = assertCanaryOperator(projectId);
        if (denied != null) return denied;
        var status = strategyService.canaryStatus(k8sNamespace, projectCode(projectId));
        if (!Boolean.TRUE.equals(status.get("exists"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前没有进行中的金丝雀部署"));
        }
        StringBuilder logBuf = new StringBuilder();
        boolean ok = strategyService.promoteCanary(k8sNamespace,
                (String) status.get("canaryName"), (String) status.get("stableName"), logBuf);
        return ok
                ? ResponseEntity.ok(Map.of("success", true, "log", logBuf.toString()))
                : ResponseEntity.badRequest().body(Map.of("error", "全量提升失败", "log", logBuf.toString()));
    }

    /** 金丝雀回滚：删除 canary，流量回落稳定版（权限同部署审批） */
    @PostMapping("/canary-rollback")
    public ResponseEntity<?> rollbackCanary(@RequestParam Long projectId) {
        ResponseEntity<?> denied = assertCanaryOperator(projectId);
        if (denied != null) return denied;
        var status = strategyService.canaryStatus(k8sNamespace, projectCode(projectId));
        if (!Boolean.TRUE.equals(status.get("exists"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "当前没有进行中的金丝雀部署"));
        }
        StringBuilder logBuf = new StringBuilder();
        boolean ok = strategyService.rollbackCanaryDeployment(k8sNamespace,
                (String) status.get("canaryName"), logBuf);
        return ok
                ? ResponseEntity.ok(Map.of("success", true, "log", logBuf.toString()))
                : ResponseEntity.badRequest().body(Map.of("error", "回滚失败", "log", logBuf.toString()));
    }

    private String projectCode(Long projectId) {
        return projectRepository.findById(projectId).map(Project::getCode).orElse("");
    }

    /** 金丝雀操作与部署审批同权限：项目 OWNER/MAINTAINER 或全局 ADMIN/MANAGER */
    private ResponseEntity<?> assertCanaryOperator(Long projectId) {
        accessService.assertCanAccessProject(projectId);
        String username = accessService.currentUsername();
        String role = accessService.currentGlobalRole();
        if (!memberService.canApproveDeployment(projectId, username, role)) {
            return ResponseEntity.status(403).body(Map.of("error", "金丝雀操作需要项目维护者（OWNER/MAINTAINER）及以上权限"));
        }
        return null;
    }

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
