package com.devops.platform.controller;

import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import com.devops.platform.service.GitPlatformService;
import com.devops.platform.service.GitPlatformService.*;
import com.devops.platform.service.ProjectAccessService;
import com.devops.platform.service.ProjectMemberService;
import com.devops.platform.service.SecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Git 平台集成接口
 * <p>
 * 读接口（分支 / 提交 / PR / 运行记录）：登录且能访问该项目即可。
 * 写接口（保存凭证 / 触发 Actions / 打 tag 发版）：要求项目 OWNER / MAINTAINER 或全局 ADMIN / MANAGER。
 * <p>
 * 凭证解析优先级：请求头 X-Git-Token（兼容旧用法） > 项目里加密保存的 gitToken。
 * 项目级凭证通过 PUT /{projectId}/config 维护，AES-GCM 加密后落库，接口从不回传明文。
 */
@Slf4j
@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
public class GitController {

    private final GitPlatformService gitPlatformService;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService accessService;
    private final ProjectMemberService memberService;
    private final SecretService secretService;

    // ==================== 仓库信息 / 分支 / 提交 / PR ====================

    /** 获取项目的 Git 仓库信息 */
    @GetMapping("/{projectId}/info")
    public ResponseEntity<?> getRepoInfo(@PathVariable Long projectId) {
        accessService.assertCanAccessProject(projectId);
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        if (project.getGitUrl() == null || project.getGitUrl().isBlank()) {
            return ResponseEntity.ok(Map.of("error", "项目未配置 Git 仓库地址"));
        }
        GitRepoInfo info = gitPlatformService.parseRepoUrl(project.getGitUrl());
        if (info == null || "unknown".equals(info.platform)) {
            return ResponseEntity.ok(Map.of("error", "无法解析 Git 仓库地址，仅支持 GitHub/GitLab/Gitee"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", info.platform);
        m.put("owner", info.owner != null ? info.owner : "");
        m.put("repo", info.repo != null ? info.repo : "");
        m.put("apiBase", info.apiBase != null ? info.apiBase : "");
        return ResponseEntity.ok(m);
    }

    /** 获取分支列表 */
    @GetMapping("/{projectId}/branches")
    public ResponseEntity<?> listBranches(@PathVariable Long projectId,
                                           @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        accessService.assertCanAccessProject(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null) return ResponseEntity.badRequest().body(Map.of("error", "无法解析仓库信息"));
        List<GitBranch> branches = gitPlatformService.listBranches(info, resolveToken(project, headerToken));
        return ResponseEntity.ok(branches);
    }

    /** 获取提交历史 */
    @GetMapping("/{projectId}/commits")
    public ResponseEntity<?> listCommits(@PathVariable Long projectId,
                                          @RequestParam(defaultValue = "main") String branch,
                                          @RequestParam(defaultValue = "20") int limit,
                                          @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        accessService.assertCanAccessProject(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null) return ResponseEntity.badRequest().body(Map.of("error", "无法解析仓库信息"));
        String token = resolveToken(project, headerToken);
        // 分支未显式指定时用远端默认分支，避免写死 main 而仓库默认是 master
        if (branch == null || branch.isBlank() || "main".equals(branch)) {
            String def = gitPlatformService.defaultBranch(info, token);
            if (def != null) branch = def;
        }
        List<GitCommit> commits = gitPlatformService.listCommits(info, branch, limit, token);
        return ResponseEntity.ok(commits);
    }

    /** 获取 MR/PR 列表 */
    @GetMapping("/{projectId}/merge-requests")
    public ResponseEntity<?> listMergeRequests(@PathVariable Long projectId,
                                                @RequestParam(defaultValue = "open") String state,
                                                @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        accessService.assertCanAccessProject(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null) return ResponseEntity.badRequest().body(Map.of("error", "无法解析仓库信息"));
        List<GitMR> mrs = gitPlatformService.listMRs(info, state, resolveToken(project, headerToken));
        return ResponseEntity.ok(mrs);
    }

    /** 创建 MR/PR */
    @PostMapping("/{projectId}/merge-requests")
    public ResponseEntity<?> createMergeRequest(@PathVariable Long projectId,
                                                 @RequestBody Map<String, Object> body,
                                                 @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        assertCanManage(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null) return ResponseEntity.badRequest().body(Map.of("error", "无法解析仓库信息"));

        String title = (String) body.getOrDefault("title", "Merge Request");
        String sourceBranch = (String) body.getOrDefault("sourceBranch", "");
        String targetBranch = (String) body.getOrDefault("targetBranch", "main");
        String description = (String) body.getOrDefault("description", "");
        if (sourceBranch.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "源分支不能为空"));
        }
        GitMR mr = gitPlatformService.createMR(info, title, sourceBranch, targetBranch, description,
                resolveToken(project, headerToken));
        if (mr != null) {
            return ResponseEntity.ok(Map.of("success", true, "mergeRequest", mr));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "创建失败，请检查 Git Token 和分支名"));
    }

    // ==================== GitHub 凭证配置 ====================

    /** 读取 GitHub 配置状态（不返回 token 明文） */
    @GetMapping("/{projectId}/config")
    public ResponseEntity<?> getConfig(@PathVariable Long projectId) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        accessService.assertCanAccessProject(projectId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasToken", project.getGitToken() != null && !project.getGitToken().isBlank());
        m.put("workflow", project.getGithubWorkflow() != null ? project.getGithubWorkflow() : "");
        m.put("gitUrl", project.getGitUrl() != null ? project.getGitUrl() : "");
        m.put("branch", project.getGitBranch() != null ? project.getGitBranch() : "");
        GitRepoInfo info = requireRepo(project);
        if (info != null) {
            m.put("platform", info.platform);
            m.put("owner", info.owner != null ? info.owner : "");
            m.put("repo", info.repo != null ? info.repo : "");
        } else {
            m.put("platform", project.getGitUrl() != null && project.getGitUrl().contains("github.com") ? "github" : "unknown");
        }
        return ResponseEntity.ok(m);
    }

    /**
     * 保存 GitHub 配置。
     * body.token 不存在 = 不改动已有凭证；传空串 = 清除凭证；传值 = 加密后覆盖。
     */
    @PutMapping("/{projectId}/config")
    public ResponseEntity<?> saveConfig(@PathVariable Long projectId,
                                         @RequestBody Map<String, Object> body) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        assertCanManage(projectId);

        if (body.containsKey("token")) {
            Object raw = body.get("token");
            String t = raw == null ? "" : String.valueOf(raw).trim();
            project.setGitToken(t.isEmpty() ? null : secretService.encrypt(t));
        }
        if (body.containsKey("workflow")) {
            Object raw = body.get("workflow");
            project.setGithubWorkflow(raw == null ? "" : String.valueOf(raw).trim());
        }
        projectRepository.save(project);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("hasToken", project.getGitToken() != null && !project.getGitToken().isBlank());
        m.put("workflow", project.getGithubWorkflow() != null ? project.getGithubWorkflow() : "");
        return ResponseEntity.ok(m);
    }

    // ==================== GitHub Actions ====================

    /** 触发 workflow_dispatch（云端构建） */
    @PostMapping("/{projectId}/actions/dispatch")
    public ResponseEntity<?> dispatch(@PathVariable Long projectId,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        assertCanManage(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null || !"github".equals(info.platform)) {
            return bad("项目未配置 GitHub 仓库地址（Actions 仅支持 GitHub）");
        }
        String token = resolveToken(project, headerToken);
        String wf = paramOr(body, "workflow", project.getGithubWorkflow());
        String ref = paramOr(body, "ref", project.getGitBranch());
        if (ref == null || ref.isBlank()) {
            String def = gitPlatformService.defaultBranch(info, token);
            ref = def != null ? def : "main";
        }
        String err = gitPlatformService.dispatchWorkflow(info, wf, ref, token);
        if (err != null) return bad(err);
        return ResponseEntity.ok(Map.of("success", true,
                "message", "已触发 GitHub Actions（workflow: " + wf + "，ref: " + ref + "）"));
    }

    /** 最近的工作流运行记录 */
    @GetMapping("/{projectId}/actions/runs")
    public ResponseEntity<?> runs(@PathVariable Long projectId,
                                   @RequestParam(defaultValue = "10") int limit,
                                   @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        accessService.assertCanAccessProject(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null) return ResponseEntity.badRequest().body(Map.of("error", "无法解析仓库信息"));
        return ResponseEntity.ok(gitPlatformService.listWorkflowRuns(
                info, project.getGithubWorkflow(), limit, resolveToken(project, headerToken)));
    }

    /** 某次运行的 job / 步骤明细 */
    @GetMapping("/{projectId}/actions/runs/{runId}/jobs")
    public ResponseEntity<?> runJobs(@PathVariable Long projectId,
                                      @PathVariable long runId,
                                      @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        accessService.assertCanAccessProject(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null) return ResponseEntity.badRequest().body(Map.of("error", "无法解析仓库信息"));
        return ResponseEntity.ok(gitPlatformService.listRunJobs(info, runId, resolveToken(project, headerToken)));
    }

    /**
     * 创建 tag → 触发仓库里「tag → build → Release」的 workflow，由 Actions 完成发布。
     */
    @PostMapping("/{projectId}/tags")
    public ResponseEntity<?> createTag(@PathVariable Long projectId,
                                        @RequestBody Map<String, Object> body,
                                        @RequestHeader(value = "X-Git-Token", required = false) String headerToken) {
        Project project = loadProject(projectId);
        if (project == null) return ResponseEntity.notFound().build();
        assertCanManage(projectId);
        GitRepoInfo info = requireRepo(project);
        if (info == null || !"github".equals(info.platform)) {
            return bad("项目未配置 GitHub 仓库地址（发布仅支持 GitHub）");
        }
        String token = resolveToken(project, headerToken);
        String tag = paramOr(body, "tag", null);
        if (tag == null) return bad("tag 不能为空");
        String ref = paramOr(body, "ref", project.getGitBranch());
        if (ref == null || ref.isBlank()) {
            String def = gitPlatformService.defaultBranch(info, token);
            ref = def != null ? def : "main";
        }
        String err = gitPlatformService.createTag(info, tag, ref, token);
        if (err != null) return bad(err);
        return ResponseEntity.ok(Map.of("success", true,
                "message", "已创建 tag " + tag + "（基于 " + ref + "），GitHub Actions 将自动构建并发布 Release"));
    }

    // ==================== 内部工具 ====================

    private Project loadProject(Long projectId) {
        return projectRepository.findById(projectId).orElse(null);
    }

    /** 解析仓库信息；平台不支持或未配置地址时返回 null */
    private GitRepoInfo requireRepo(Project project) {
        if (project.getGitUrl() == null || project.getGitUrl().isBlank()) return null;
        GitRepoInfo info = gitPlatformService.parseRepoUrl(project.getGitUrl());
        if (info == null || "unknown".equals(info.platform) || info.owner == null || info.repo == null) return null;
        return info;
    }

    /** 生效的 Git Token：请求头优先，其次项目里加密保存的 */
    private String resolveToken(Project project, String headerToken) {
        if (headerToken != null && !headerToken.isBlank()) return headerToken;
        String enc = project.getGitToken();
        if (enc == null || enc.isBlank()) return null;
        try {
            return secretService.decrypt(enc);
        } catch (Exception e) {
            log.warn("解密 Git Token 失败 (projectId={}): {}", project.getId(), e.getMessage());
            return null;
        }
    }

    /** 写操作（保存凭证 / 触发 Actions / 打 tag）需要项目维护者及以上 */
    private void assertCanManage(Long projectId) {
        accessService.assertCanAccessProject(projectId);
        if (accessService.isGlobalAdminOrManager()) return;
        String username = accessService.currentUsername();
        String globalRole = accessService.currentGlobalRole();
        if (!memberService.canApproveDeployment(projectId, username, globalRole)) {
            throw new AccessDeniedException("需要项目维护者（OWNER / MAINTAINER）及以上权限");
        }
    }

    private String paramOr(Map<String, Object> body, String key, String fallback) {
        if (body != null && body.get(key) != null) {
            String v = String.valueOf(body.get(key)).trim();
            if (!v.isEmpty()) return v;
        }
        return fallback;
    }

    private ResponseEntity<?> bad(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return ResponseEntity.badRequest().body(m);
    }
}
