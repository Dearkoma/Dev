package com.devops.platform.service;

import com.devops.platform.entity.GitOpsApplication;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.GitOpsApplicationRepository;
import com.devops.platform.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * GitOps 同步服务 — 真实执行 kubectl apply 将 Git 仓库中的 K8s YAML 部署到集群
 *
 * Git 仓库 → (D 平台 workspace 克隆) → kubectl apply → K8s 集群
 * 同步状态 (syncStatus) 与健康状态 (healthStatus) 反映真实 kubectl 结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitOpsService {

    private final GitOpsApplicationRepository gitOpsRepository;
    private final ProjectRepository projectRepository;

    @Value("${devops.pipeline.workspace-dir:./data/workspace}")
    private String workspaceDir;

    /**
     * 同步单个 GitOps 应用：kubectl apply 目标路径下的所有 YAML
     */
    public GitOpsApplication sync(Long applicationId) {
        GitOpsApplication app = gitOpsRepository.findById(applicationId).orElse(null);
        if (app == null) throw new RuntimeException("GitOps 应用不存在: " + applicationId);
        return sync(app);
    }

    public GitOpsApplication sync(GitOpsApplication app) {
        app.setSyncStatus("SYNCING");
        gitOpsRepository.save(app);

        try {
            // 1. 解析目标路径：项目 workspace 下 + gitPath
            Path manifestPath = resolveManifestPath(app);
            if (manifestPath == null || !Files.exists(manifestPath)) {
                app.setSyncStatus("OUT_OF_SYNC");
                app.setHealthStatus("DEGRADED");
                log.warn("[GITOPS] 目标路径不存在: app={}, path={}", app.getName(), manifestPath);
                return gitOpsRepository.save(app);
            }

            String namespace = app.getTargetNamespace() != null && !app.getTargetNamespace().isBlank()
                    ? app.getTargetNamespace() : "devops";

            log.info("[GITOPS] 同步应用: {} → kubectl apply -f {} -n {}",
                    app.getName(), manifestPath, namespace);

            // 2. kubectl apply -f <path> -n <namespace>
            ProcessBuilder pb = new ProcessBuilder("kubectl", "apply", "-f",
                    manifestPath.toString(), "-n", namespace);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = readAll(proc);
            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);

            if (!finished) {
                proc.destroyForcibly();
                app.setSyncStatus("OUT_OF_SYNC");
                app.setHealthStatus("DEGRADED");
                log.warn("[GITOPS] kubectl apply 超时: {}", app.getName());
                return gitOpsRepository.save(app);
            }

            int exit = proc.exitValue();
            if (exit != 0) {
                app.setSyncStatus("OUT_OF_SYNC");
                app.setHealthStatus("DEGRADED");
                log.warn("[GITOPS] kubectl apply 失败 (exit={}): {}", exit, output.trim());
            } else {
                app.setSyncStatus("SYNCED");
                app.setHealthStatus("HEALTHY");
                app.setLastSyncAt(LocalDateTime.now());
                log.info("[GITOPS] 同步成功: {} → {}", app.getName(), output.trim());
            }

            return gitOpsRepository.save(app);

        } catch (Exception e) {
            log.error("[GITOPS] 同步异常: app={}, error={}", app.getName(), e.getMessage(), e);
            app.setSyncStatus("OUT_OF_SYNC");
            app.setHealthStatus("DEGRADED");
            return gitOpsRepository.save(app);
        }
    }

    /**
     * 解析 K8s manifest 路径 = workspace/<projectCode>/<gitPath>
     */
    private Path resolveManifestPath(GitOpsApplication app) {
        if (app.getProjectId() == null) return null;
        Project project = projectRepository.findById(app.getProjectId()).orElse(null);
        if (project == null || project.getCode() == null) return null;

        Path root = Paths.get(workspaceDir).toAbsolutePath().normalize();
        Path base = root.resolve(project.getCode());

        String gitPath = app.getGitPath();
        if (gitPath == null || gitPath.isBlank() || ".".equals(gitPath)) {
            return base;
        }
        return base.resolve(gitPath);
    }

    private String readAll(Process proc) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
