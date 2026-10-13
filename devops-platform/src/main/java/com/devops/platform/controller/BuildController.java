package com.devops.platform.controller;

import com.devops.platform.entity.Build;
import com.devops.platform.repository.BuildRepository;
import com.devops.platform.service.BuildService;
import com.devops.platform.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import com.devops.platform.aspect.AuditAspect;

@RestController
@RequestMapping("/api/builds")
@RequiredArgsConstructor
public class BuildController {

    private final BuildService buildService;
    private final BuildRepository buildRepository;
    private final ProjectAccessService accessService;

    @AuditAspect.Audit(action = "TRIGGER", resource = "BUILD")
    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(
            @RequestParam Long projectId,
            @RequestParam Long pipelineId,
            @RequestBody(required = false) Map<String, Object> body,
            Principal principal) {
        accessService.assertCanAccessProject(projectId);
        try {
            // 触发人必须从 JWT 取, 不允许前端伪造 (对齐 DeploymentController)
            String triggeredBy = principal != null ? principal.getName() : "unknown";
            String buildParams = null;
            String branch = null;
            boolean skipDocker = false;
            boolean skipK8s = false;
            String dbName = null;
            if (body != null) {
                if (body.containsKey("buildParams")) {
                    Object p = body.get("buildParams");
                    buildParams = p == null ? null : (p instanceof String ? (String) p : p.toString());
                }
                if (body.containsKey("branch")) {
                    Object b = body.get("branch");
                    branch = b == null ? null : (b instanceof String ? (String) b : b.toString());
                }
                // 接收独立的跳过标志（向后兼容旧的 deployTarget 字段）
                if (body.containsKey("skipDocker")) {
                    skipDocker = Boolean.TRUE.equals(body.get("skipDocker"));
                }
                if (body.containsKey("skipK8s")) {
                    skipK8s = Boolean.TRUE.equals(body.get("skipK8s"));
                }
                // 兼容旧版 deployTarget 参数
                if (body.containsKey("deployTarget") && !body.containsKey("skipDocker") && !body.containsKey("skipK8s")) {
                    String dt = body.get("deployTarget").toString().toUpperCase();
                    switch (dt) {
                        case "NONE":  skipDocker = true;  skipK8s = true;  break;
                        case "DOCKER": skipDocker = false; skipK8s = true;  break;
                        case "K8S":    skipDocker = false; skipK8s = false; break;
                    }
                }
                // 数据库名（用于数据库隔离）
                if (body.containsKey("dbName")) {
                    Object d = body.get("dbName");
                    dbName = d == null ? null : d.toString();
                }
            }
            Build build = buildService.triggerBuild(projectId, pipelineId, triggeredBy,
                    buildParams, branch, skipDocker, skipK8s, dbName);
            // 设置部署策略
            if (body != null && body.containsKey("deployStrategy")) {
                String strategy = body.get("deployStrategy").toString();
                build.setDeployStrategy(strategy);
                buildRepository.save(build);
            }
            return ResponseEntity.ok(build);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Build> getBuilds(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status) {
        if (projectId != null) {
            accessService.assertCanAccessProject(projectId);
            return buildService.getBuilds(projectId, status);
        }
        return accessService.filterByAccessibleProjects(buildService.getBuilds(null, status), Build::getProjectId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Build> getOne(@PathVariable Long id) {
        accessService.assertCanAccessBuild(id);
        Build b = buildService.getBuild(id);
        return b == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(b);
    }

    @GetMapping("/{id}/log")
    public ResponseEntity<String> getLog(@PathVariable Long id) {
        accessService.assertCanAccessBuild(id);
        String log = buildService.getBuildLog(id);
        return ResponseEntity.ok(log);
    }

    @AuditAspect.Audit(action = "CANCEL", resource = "BUILD")
    @DeleteMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        accessService.assertCanAccessBuild(id);
        buildService.cancelBuild(id);
        return ResponseEntity.ok().build();
    }

    @AuditAspect.Audit(action = "DELETE", resource = "BUILD")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBuild(@PathVariable Long id) {
        accessService.assertCanAccessBuild(id);
        try {
            buildService.deleteBuild(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 从失败步骤断点续跑 */
    @AuditAspect.Audit(action = "TRIGGER", resource = "BUILD")
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retryBuild(@PathVariable Long id) {
        accessService.assertCanAccessBuild(id);
        try {
            Build retried = buildService.retryBuild(id);
            return ResponseEntity.ok(retried);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 检查项目工作目录状态 — 验证 Git 克隆情况和构建文件 */
    @GetMapping("/workspace-check")
    public ResponseEntity<?> workspaceCheck(@RequestParam Long projectId) {
        accessService.assertCanAccessProject(projectId);
        return ResponseEntity.ok(buildService.checkWorkspace(projectId));
    }

    /** 审批通过 — 继续执行等待中的 APPROVAL 步骤 */
    @AuditAspect.Audit(action = "APPROVE", resource = "BUILD")
    @PostMapping("/{id}/approve-step")
    public ResponseEntity<?> approveStep(@PathVariable Long id, Principal principal) {
        accessService.assertCanAccessBuild(id);
        try {
            String approvedBy = principal != null ? principal.getName() : "admin";
            buildService.approveStep(id, approvedBy);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 审批拒绝 — 终止等待中的 APPROVAL 步骤 */
    @AuditAspect.Audit(action = "REJECT", resource = "BUILD")
    @PostMapping("/{id}/reject-step")
    public ResponseEntity<?> rejectStep(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body, Principal principal) {
        accessService.assertCanAccessBuild(id);
        try {
            String rejectedBy = principal != null ? principal.getName() : "admin";
            String reason = body != null ? body.getOrDefault("reason", "") : "";
            buildService.rejectStep(id, rejectedBy, reason);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 检查数据库名是否与已有项目冲突 */
    @GetMapping("/check-db-conflict")
    public ResponseEntity<?> checkDbConflict(@RequestParam String dbName, @RequestParam Long projectId) {
        accessService.assertCanAccessProject(projectId);
        try {
            var result = buildService.checkDbConflict(dbName, projectId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
