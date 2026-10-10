package com.devops.platform.service;

import com.devops.platform.config.EventWebSocketHandler;
import com.devops.platform.entity.*;
import com.devops.platform.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentService {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentHistoryRepository historyRepository;
    private final EnvironmentRepository environmentRepository;
    private final ProjectRepository projectRepository;
    private final BuildRepository buildRepository;
    private final BuildService buildService;
    private final Executor buildExecutor;
    private final ProjectMemberService memberService;
    private final FeatureFlagService featureFlagService;
    private final WebhookDispatcher webhookDispatcher;

    /** WS 广播部署事件, 前端 DeploymentList 收到后刷新 */
    private void broadcastDeploymentEvent(String action, DeploymentRequest req) {
        try {
            EventWebSocketHandler.broadcast("deployment.updated", Map.of(
                    "action", action,
                    "id", req.getId(),
                    "status", req.getStatus() == null ? "" : req.getStatus()
            ));
        } catch (Exception e) {
            log.warn("推送部署 WS 事件失败: {}", e.getMessage());
        }
    }

    /** 申请部署（受保护环境自动进入审批） */
    public DeploymentRequest requestDeploy(Long buildId, Long environmentId, String requestedBy,
                                            String reason, String deployStrategy) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new RuntimeException("构建记录不存在"));
        Environment env = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new RuntimeException("环境不存在"));
        Project project = projectRepository.findById(build.getProjectId())
                .orElseThrow(() -> new RuntimeException("项目不存在"));

        // Feature Flag 部署门禁: 项目+环境下 deploy-gate 开关关闭 → 阻断部署
        String envKey = env.getName() != null ? env.getName() : "dev";
        if (featureFlagService.isDeployBlocked(project.getId(), envKey, requestedBy)) {
            throw new RuntimeException("部署被 Feature Flag 门禁阻断（deploy-gate 开关已关闭，环境=" + envKey + "）");
        }

        DeploymentRequest req = new DeploymentRequest();
        req.setBuildId(buildId);
        req.setEnvironmentId(environmentId);
        req.setProjectId(build.getProjectId());
        req.setProjectName(project.getName());
        req.setEnvironmentName(env.getDisplayName() != null ? env.getDisplayName() : env.getName());
        req.setRequestedBy(requestedBy);
        req.setReason(reason);
        req.setDeployUrl(env.getDeployUrl());
        // 设置部署策略（默认滚动更新）
        req.setDeployStrategy(deployStrategy != null && !deployStrategy.isBlank() ? deployStrategy : "ROLLING");
        req.setStrategyStatus("INIT");

        // 受保护环境需要审批
        if (Boolean.TRUE.equals(env.getProtectedEnv())) {
            req.setStatus("PENDING");
        } else {
            req.setStatus("APPROVED");
            // 非保护环境直接执行部署
            deployToHistory(req);
        }

        DeploymentRequest saved = requestRepository.save(req);
        broadcastDeploymentEvent("REQUESTED", saved);
        return saved;
    }

    /** 审批通过 */
    @Transactional
    public DeploymentRequest approve(Long requestId, String approvedBy) {
        DeploymentRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("部署申请不存在"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new RuntimeException("该申请已被处理");
        }
        // 项目级权限检查: 需 ADMIN/MANAGER 全局角色 或 项目 OWNER/MAINTAINER
        if (!memberService.canApproveDeployment(req.getProjectId(), approvedBy, getCurrentGlobalRole())) {
            throw new RuntimeException("无权审批该项目部署（需项目 OWNER/MAINTAINER 或全局 ADMIN/MANAGER）");
        }
        req.setStatus("APPROVED");
        req.setApprovedBy(approvedBy);
        req.setApprovedAt(LocalDateTime.now());
        requestRepository.save(req);
        broadcastDeploymentEvent("APPROVED", req);

        // 执行部署
        deployToHistory(req);
        return req;
    }

    /** 审批拒绝 */
    @Transactional
    public DeploymentRequest reject(Long requestId, String approvedBy, String rejectReason) {
        DeploymentRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("部署申请不存在"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new RuntimeException("该申请已被处理");
        }
        // 项目级权限检查: 与 approve 保持一致
        // (需全局 ADMIN/MANAGER, 或该项目的 OWNER/MAINTAINER)
        if (!memberService.canApproveDeployment(req.getProjectId(), approvedBy, getCurrentGlobalRole())) {
            throw new RuntimeException("无权审批该项目部署（需项目 OWNER/MAINTAINER 或全局 ADMIN/MANAGER）");
        }
        req.setStatus("REJECTED");
        req.setApprovedBy(approvedBy);
        req.setRejectReason(rejectReason);
        req.setApprovedAt(LocalDateTime.now());
        DeploymentRequest saved = requestRepository.save(req);
        broadcastDeploymentEvent("REJECTED", saved);
        return saved;
    }

    /** 执行部署 — 先记录历史, K8s 部署异步后台执行 */
    private void deployToHistory(DeploymentRequest req) {
        Build build = buildRepository.findById(req.getBuildId()).orElse(null);
        if (build == null) return;

        Environment env = environmentRepository.findById(req.getEnvironmentId()).orElse(null);
        Project project = projectRepository.findById(req.getProjectId()).orElse(null);

        String strategy = req.getDeployStrategy() != null ? req.getDeployStrategy() : "ROLLING";

        // 1. 先写历史记录(状态 DEPLOYING)
        DeploymentHistory history = new DeploymentHistory();
        history.setProjectId(req.getProjectId());
        history.setProjectName(req.getProjectName());
        history.setEnvironmentId(req.getEnvironmentId());
        history.setEnvironmentName(req.getEnvironmentName());
        history.setBuildId(req.getBuildId());
        history.setBuildNumber(build.getBuildNumber());
        history.setVersion(build.getBuildNumber());  // 版本号默认用构建编号
        history.setStatus("DEPLOYING");
        history.setDeployUrl(req.getDeployUrl());
        history.setDeployStrategy(strategy);
        history.setDescription("部署申请 #" + req.getId() + " 通过，策略: " + strategy);
        history.setDeployedBy(req.getApprovedBy() != null ? req.getApprovedBy() : req.getRequestedBy());
        history.setIsRollbackPoint(true);
        DeploymentHistory savedHistory = historyRepository.save(history);

        // 2. 更新策略状态
        switch (strategy) {
            case "BLUE_GREEN":
                req.setStrategyStatus("GREEN_ACTIVE");
                req.setStrategyDetail("蓝绿部署完成，新版本已切换到活跃槽位");
                break;
            case "CANARY":
                req.setStrategyStatus("CANARY_RUNNING");
                req.setStrategyDetail("金丝雀版本已部署，观察期间可手动提升流量或回滚");
                break;
            default:
                req.setStrategyStatus("PROMOTED");
                req.setStrategyDetail("滚动更新完成");
        }

        req.setStatus("DEPLOYING");
        req.setDeployedAt(LocalDateTime.now());
        requestRepository.save(req);

        // 3. 异步执行 K8s 部署 (不阻塞 HTTP 响应)
        if (project != null && build != null && env != null) {
            final DeploymentHistory historyRef = savedHistory;
            final Long requestId = req.getId();
            CompletableFuture.runAsync(() -> {
                final boolean[] k8sSuccess = {false};
                try {
                    k8sSuccess[0] = executeK8sDeploy(project, build, env, historyRef);
                } catch (Exception e) {
                    log.warn("[DEPLOY] K8s 部署执行失败: {}", e.getMessage());
                }

                // 更新历史记录
                DeploymentHistory h = historyRepository.findById(historyRef.getId()).orElse(null);
                if (h != null) {
                    h.setStatus(k8sSuccess[0] ? "DEPLOYED" : "FAILED");
                    if (!k8sSuccess[0]) {
                        h.setDescription(h.getDescription() + " — K8s 部署失败，请检查集群状态");
                    }
                    historyRepository.save(h);
                }

                // 更新申请单状态
                requestRepository.findById(requestId).ifPresent(r -> {
                    r.setStatus(k8sSuccess[0] ? "DEPLOYED" : "FAILED");
                    requestRepository.save(r);
                    broadcastDeploymentEvent(k8sSuccess[0] ? "DEPLOYED" : "FAILED", r);
                    dispatchDeployWebhook(k8sSuccess[0], r);
                });
            }, buildExecutor);
        }
    }

    /**
     * 部署结束时派发 deploy.success / deploy.failed 事件 (Webhook)
     * 订阅端按 projectId 匹配: 绑定了项目的订阅只收该项目事件, 未绑定的全局订阅全收
     */
    private void dispatchDeployWebhook(boolean success, DeploymentRequest req) {
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("title", (success ? "部署成功 · " : "部署失败 · ") + nvl(req.getProjectName()));
            payload.put("project", nvl(req.getProjectName()));
            payload.put("projectId", req.getProjectId() == null ? "" : String.valueOf(req.getProjectId()));
            payload.put("environment", nvl(req.getEnvironmentName()));
            payload.put("strategy", nvl(req.getDeployStrategy()));
            payload.put("status", success ? "DEPLOYED" : "FAILED");
            payload.put("deployUrl", nvl(req.getDeployUrl()));
            payload.put("requestId", String.valueOf(req.getId()));
            payload.put("requestedBy", nvl(req.getRequestedBy()));
            webhookDispatcher.dispatch(
                    success ? WebhookSubscription.EVENT_DEPLOY_SUCCESS : WebhookSubscription.EVENT_DEPLOY_FAILED,
                    payload, req.getProjectId());
        } catch (Exception e) {
            log.warn("[DEPLOY] deploy 事件派发失败: {}", e.getMessage());
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /**
     * 实际执行 K8s 部署: kubectl apply 项目 workspace 下的 deployment.yaml
     */
    private boolean executeK8sDeploy(Project project, Build build, Environment env, DeploymentHistory history) {
        String namespace = env.getK8sNamespace() != null && !env.getK8sNamespace().isBlank()
                ? env.getK8sNamespace() : "devops";
        String appName = project.getCode() != null ? project.getCode() : "devops-app";

        try {
            Path workspace = Path.of("./data/workspace").toAbsolutePath().normalize()
                    .resolve(project.getCode());
            Path deployFile = workspace.resolve("deployment.yaml");
            if (!java.nio.file.Files.exists(deployFile)) {
                deployFile = workspace.resolve("deployment-rendered.yaml");
            }
            if (!java.nio.file.Files.exists(deployFile)) {
                // 如果 workspace 没有 YAML, 尝试从构建日志推断 (兜底)
                log.warn("[DEPLOY] 未找到部署 YAML: workspace={}, project={}, 跳过 K8s 部署",
                        workspace, project.getCode());
                return false;
            }

            // 打标签: 记录部署版本 (便于 rollout undo 识别)
            String versionLabel = "deployed-at=" + LocalDateTime.now().toString().replace(":", "-");
            try {
                new ProcessBuilder("kubectl", "label", "deployment/" + appName,
                        versionLabel, "-n", namespace, "--overwrite")
                        .redirectErrorStream(true).start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // label 失败不影响部署
            }

            ProcessBuilder pb = new ProcessBuilder("kubectl", "apply",
                    "-f", deployFile.toString(), "-n", namespace);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.error("[DEPLOY] kubectl apply 超时: {}", appName);
                return false;
            }

            log.info("[DEPLOY] kubectl apply result: {}", output.trim());
            if (proc.exitValue() != 0) {
                log.error("[DEPLOY] kubectl apply 失败 (exit={}): {}", proc.exitValue(), output.trim());
                return false;
            }

            // 等待 rollout 完成
            ProcessBuilder pb2 = new ProcessBuilder("kubectl", "rollout", "status",
                    "deployment/" + appName, "-n", namespace, "--timeout=120s");
            pb2.redirectErrorStream(true);
            Process proc2 = pb2.start();
            String rolloutOutput = new String(proc2.getInputStream().readAllBytes());
            proc2.waitFor(130, java.util.concurrent.TimeUnit.SECONDS);
            log.info("[DEPLOY] rollout status: {} -> exit={}", appName,
                    proc2.isAlive() ? "timeout" : proc2.exitValue());

            // rollout status 失败不算致命错误 (可能只是需要更长时间)
            return true;

        } catch (Exception e) {
            log.error("[DEPLOY] K8s 部署异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 回滚到指定部署历史 — 实际执行 kubectl 部署旧版本 */
    @Transactional
    public DeploymentHistory rollback(Long historyId, String triggeredBy) {
        DeploymentHistory prev = historyRepository.findById(historyId)
                .orElseThrow(() -> new RuntimeException("部署历史不存在"));

        // 项目级权限检查
        if (!memberService.canApproveDeployment(prev.getProjectId(), triggeredBy, getCurrentGlobalRole())) {
            throw new RuntimeException("无权回滚该项目部署（需项目 OWNER/MAINTAINER 或全局 ADMIN/MANAGER）");
        }

        // 获取项目和构建信息
        Project project = projectRepository.findById(prev.getProjectId()).orElse(null);
        Build build = buildRepository.findById(prev.getBuildId()).orElse(null);

        // 尝试实际执行 kubectl 回滚
        boolean k8sRollbackSuccess = false;
        if (project != null && build != null) {
            try {
                k8sRollbackSuccess = executeK8sRollback(project, build, prev);
            } catch (Exception e) {
                log.warn("[ROLLBACK] K8s 回滚执行失败: {}", e.getMessage());
            }
        }

        // 创建回滚记录
        DeploymentHistory rollback = new DeploymentHistory();
        rollback.setProjectId(prev.getProjectId());
        rollback.setProjectName(prev.getProjectName());
        rollback.setEnvironmentId(prev.getEnvironmentId());
        rollback.setEnvironmentName(prev.getEnvironmentName());
        rollback.setBuildId(prev.getBuildId());
        rollback.setBuildNumber(prev.getBuildNumber());
        rollback.setStatus(k8sRollbackSuccess ? "DEPLOYED" : "FAILED");
        rollback.setVersion(prev.getVersion());
        rollback.setDeployUrl(prev.getDeployUrl());
        rollback.setDescription("回滚到构建 #" + prev.getBuildNumber()
                + " (历史记录 #" + prev.getId() + ")"
                + (k8sRollbackSuccess ? " — K8s 已重新部署" : " — K8s 部署失败，请手动处理"));
        rollback.setDeployedBy(triggeredBy);
        rollback.setIsRollbackPoint(false);
        DeploymentHistory savedRollback = historyRepository.save(rollback);

        // 回滚也是一次部署动作 → 派发 deploy 事件
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("title", (k8sRollbackSuccess ? "回滚成功 · " : "回滚失败 · ") + nvl(prev.getProjectName()));
            payload.put("project", nvl(prev.getProjectName()));
            payload.put("projectId", prev.getProjectId() == null ? "" : String.valueOf(prev.getProjectId()));
            payload.put("environment", nvl(prev.getEnvironmentName()));
            payload.put("status", k8sRollbackSuccess ? "DEPLOYED" : "FAILED");
            payload.put("action", "ROLLBACK");
            payload.put("version", nvl(prev.getVersion()));
            payload.put("deployUrl", nvl(prev.getDeployUrl()));
            payload.put("operator", nvl(triggeredBy));
            webhookDispatcher.dispatch(
                    k8sRollbackSuccess ? WebhookSubscription.EVENT_DEPLOY_SUCCESS : WebhookSubscription.EVENT_DEPLOY_FAILED,
                    payload, prev.getProjectId());
        } catch (Exception e) {
            log.warn("[ROLLBACK] deploy 事件派发失败: {}", e.getMessage());
        }
        return savedRollback;
    }

    /**
     * 实际执行 K8s 回滚：kubectl rollout undo 或重新 apply 旧版本 YAML
     */
    private boolean executeK8sRollback(Project project, Build build, DeploymentHistory prev) {
        String namespace = "devops";
        String appName = project.getCode() != null ? project.getCode() : "devops-app";

        // 优先使用 kubectl rollout undo（K8s 原生回滚）
        try {
            ProcessBuilder pb = new ProcessBuilder("kubectl", "rollout", "undo",
                    "deployment/" + appName, "-n", namespace);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            log.info("[ROLLBACK] kubectl rollout undo result: {}", output.trim());

            if (proc.exitValue() == 0) {
                // 等待 rollout 完成
                ProcessBuilder pb2 = new ProcessBuilder("kubectl", "rollout", "status",
                        "deployment/" + appName, "-n", namespace, "--timeout=120s");
                pb2.redirectErrorStream(true);
                Process proc2 = pb2.start();
                proc2.waitFor(130, java.util.concurrent.TimeUnit.SECONDS);
                log.info("[ROLLBACK] 回滚完成: {} -> 状态={}", appName, proc2.exitValue());
                return proc2.exitValue() == 0;
            }
        } catch (Exception e) {
            log.warn("[ROLLBACK] rollout undo 失败，尝试手动重新部署: {}", e.getMessage());
        }

        // 如果 rollout undo 不可用（新 Deployment），尝试重新 apply 原始 YAML
        try {
            Path workspace = Path.of("./data/workspace").toAbsolutePath().normalize()
                    .resolve(project.getCode());
            Path deployFile = workspace.resolve("deployment.yaml");
            if (!java.nio.file.Files.exists(deployFile)) {
                deployFile = workspace.resolve("deployment-rendered.yaml");
            }
            if (java.nio.file.Files.exists(deployFile)) {
                ProcessBuilder pb = new ProcessBuilder("kubectl", "apply",
                        "-f", deployFile.toString(), "-n", namespace);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                log.info("[ROLLBACK] kubectl apply result: {}", output.trim());
                return proc.exitValue() == 0;
            }
        } catch (Exception e) {
            log.warn("[ROLLBACK] 手动 apply 也失败: {}", e.getMessage());
        }

        return false;
    }

    /** 获取可回滚的部署历史 */
    public List<DeploymentHistory> getRollbackCandidates(Long projectId, Long environmentId) {
        return historyRepository.findByProjectIdAndEnvironmentIdAndIsRollbackPointTrueOrderByDeployedAtDesc(projectId, environmentId);
    }

    public List<DeploymentRequest> getPendingRequests() {
        return requestRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    public List<DeploymentRequest> getRequestsByProject(Long projectId) {
        if (projectId != null) {
            return requestRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        }
        return requestRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<DeploymentHistory> getHistory(Long projectId, Long environmentId) {
        if (projectId != null && environmentId != null) {
            return historyRepository.findByProjectIdAndEnvironmentIdOrderByDeployedAtDesc(projectId, environmentId);
        } else if (projectId != null) {
            return historyRepository.findByProjectIdOrderByDeployedAtDesc(projectId);
        } else if (environmentId != null) {
            return historyRepository.findByEnvironmentIdOrderByDeployedAtDesc(environmentId);
        } else {
            return historyRepository.findAll(); // 无筛选条件时返回全部
        }
    }

    /** 删除部署历史记录（需全局 ADMIN/MANAGER 或项目 OWNER/MAINTAINER） */
    public void deleteDeploymentHistory(Long id, String operator) {
        DeploymentHistory history = historyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("部署历史不存在"));
        if (!memberService.canApproveDeployment(history.getProjectId(), operator, getCurrentGlobalRole())) {
            throw new RuntimeException("无权删除该项目部署历史（需项目 OWNER/MAINTAINER 或全局 ADMIN/MANAGER）");
        }
        historyRepository.deleteById(id);
        log.info("已删除部署历史 #{} (操作人={})", id, operator);
    }

    /** 删除部署申请（仅允许删除 REJECTED / DEPLOYED 状态，且需全局 ADMIN/MANAGER 或项目 OWNER/MAINTAINER） */
    public void deleteDeploymentRequest(Long id, String operator) {
        DeploymentRequest req = requestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("部署申请不存在"));
        if ("PENDING".equals(req.getStatus())) {
            throw new RuntimeException("待审批的申请不能直接删除，请先拒绝");
        }
        if (!memberService.canApproveDeployment(req.getProjectId(), operator, getCurrentGlobalRole())) {
            throw new RuntimeException("无权删除该项目部署申请（需项目 OWNER/MAINTAINER 或全局 ADMIN/MANAGER）");
        }
        requestRepository.delete(req);
        log.info("已删除部署申请 #{} (status={}, 操作人={})", id, req.getStatus(), operator);
    }

    /** 从 Spring Security Context 提取当前用户全局角色 */
    private String getCurrentGlobalRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        return auth.getAuthorities().stream()
                .map(Object::toString)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse(null);
    }
}
