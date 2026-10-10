package com.devops.platform.service;

import com.devops.platform.entity.Build;
import com.devops.platform.entity.DeploymentHistory;
import com.devops.platform.entity.Project;
import com.devops.platform.entity.ServiceInstance;
import com.devops.platform.repository.BuildRepository;
import com.devops.platform.repository.DeploymentHistoryRepository;
import com.devops.platform.repository.ServiceInstanceRepository;
import com.devops.platform.service.GitPlatformService.GitCommit;
import com.devops.platform.service.GitPlatformService.GitRepoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/**
 * 「项目在 GitHub 上有更新」检查服务。
 * <p>
 * 用于：项目已经部署过，之后仓库又推了新提交 → 在项目管理列表里给个小提示，并可一键更新。
 * <p>
 * <b>判断口径（commit 为主，时间兜底）</b>
 * <ul>
 *   <li>已部署的 commit：最近一次部署所用构建记录的 {@code gitCommit}
 *       （由 {@code BuildService.recordWorkspaceCommit} 在 clone/pull 之后写入）</li>
 *   <li>远端最新 commit：{@code GitPlatformService.listCommits} 取该分支最新一条</li>
 *   <li>两者都有 commit → 比对 sha，最准</li>
 *   <li>老构建没有 commit（该字段以前只有 webhook 构建才写）→ 退化为
 *       「远端最新提交时间 晚于 本次部署时间」，避免老项目永远不提示</li>
 * </ul>
 * <p>
 * 远端查询结果缓存 {@value #CACHE_TTL_MS_IN_MINUTES} 分钟：GitHub 匿名 API 每小时只有 60 次，
 * 项目管理页每次刷新都打一遍会很快耗尽配额。手动「检查更新」可传 forceRefresh 跳过缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectUpdateService {

    private final DeploymentHistoryRepository deploymentHistoryRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final BuildRepository buildRepository;
    private final GitPlatformService gitPlatformService;
    private final SecretService secretService;

    /** 远端查询缓存时长 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final int CACHE_TTL_MS_IN_MINUTES = 5;
    /** 单个项目远端查询的最长等待；超时就跳过该项目，不拖垮整个列表 */
    private static final int PER_PROJECT_TIMEOUT_SECONDS = 12;

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    /** 并行查询：串行 N 个项目时，一个网络不通就要等 N × 15s，页面直接卡死 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "project-update-check");
        t.setDaemon(true);
        return t;
    });

    private record Cached(Map<String, Object> data, long at) {}

    /**
     * 批量检查。只查「部署过 + 配了 Git 地址」的项目，其余直接返回 deployed=false。
     *
     * @param projects     已按访问权限过滤过的项目
     * @param forceRefresh true = 跳过缓存（手动点「检查更新」时用）
     */
    public List<Map<String, Object>> checkAll(List<Project> projects, boolean forceRefresh) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Project> pending = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Project p : projects) {
            Cached c = forceRefresh ? null : cache.get(p.getId());
            if (c != null && now - c.at() < CACHE_TTL_MS) {
                out.add(c.data());
            } else {
                pending.add(p);
            }
        }

        if (!pending.isEmpty()) {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (Project p : pending) {
                futures.add(executor.submit(() -> checkOne(p)));
            }
            for (Future<Map<String, Object>> f : futures) {
                try {
                    out.add(f.get(PER_PROJECT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (Exception e) {
                    // 超时/异常 → 该项目本次不出结果，不阻塞整页
                    log.debug("[Update] 单个项目检查未完成: {}", e.getMessage());
                }
            }
        }

        out.sort(Comparator.comparingLong(m -> ((Number) m.getOrDefault("projectId", 0L)).longValue()));
        return out;
    }

    // ==================== 单项目检查 ====================

    private Map<String, Object> checkOne(Project project) {
        Long pid = project.getId();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("projectId", pid);
        r.put("deployed", false);
        r.put("hasUpdate", false);

        try {
            Build deployed = lastDeployedBuild(pid);
            if (deployed == null) {
                // 没部署过 → 谈不上「有更新」，前端也不显示任何东西
                return remember(pid, r);
            }

            r.put("deployed", true);
            r.put("deployedBuildId", deployed.getId());
            r.put("deployedBuildNumber", deployed.getBuildNumber());
            r.put("deployedCommit", trimCommit(deployed.getGitCommit()));
            r.put("deployedAt", toIso(deployed.getEndTime() != null ? deployed.getEndTime() : deployed.getStartTime()));
            r.put("pipelineId", deployed.getPipelineId());
            r.put("dbName", deployed.getDbName());
            r.put("branch", deployed.getBranch());

            String gitUrl = project.getGitUrl();
            if (gitUrl == null || gitUrl.isBlank()) {
                r.put("note", "项目未配置 Git 地址，无法检查更新");
                return remember(pid, r);
            }
            GitRepoInfo info = gitPlatformService.parseRepoUrl(gitUrl);
            if (info == null || "unknown".equals(info.platform) || info.owner == null || info.repo == null) {
                r.put("note", "无法解析 Git 仓库地址（仅支持 GitHub / GitLab / Gitee）");
                return remember(pid, r);
            }

            String token = resolveToken(project);
            String branch = pickBranch(project, deployed, info, token);
            GitCommit head = remoteHead(info, branch, gitUrl, token);
            if (head == null || head.sha == null || head.sha.isBlank()) {
                r.put("note", "读不到远端提交（仓库为空、无读取权限，或远端不可达）");
                return remember(pid, r);
            }

            r.put("remoteCommit", trimCommit(head.sha));
            r.put("remoteMessage", firstLine(head.message));
            r.put("remoteAuthor", head.author);
            r.put("remoteDate", head.date);
            r.put("branch", branch);
            if (head.date == null) {
                r.put("note", "GitHub API 不可用（限流或被屏蔽），本次仅按 commit 比对");
            }

            boolean byCommit = r.get("deployedCommit") != null;
            r.put("basis", byCommit ? "COMMIT" : "TIME");
            r.put("hasUpdate", hasUpdate(deployed, head, byCommit));
        } catch (Exception e) {
            log.warn("[Update] 检查项目 {} 更新失败: {}", pid, e.getMessage());
            r.put("note", "检查失败: " + e.getMessage());
        }
        return remember(pid, r);
    }

    private Map<String, Object> remember(Long pid, Map<String, Object> data) {
        cache.put(pid, new Cached(data, System.currentTimeMillis()));
        return data;
    }

    /**
     * 已部署的那个构建：优先取最近一条 DEPLOYED 的部署历史；
     * 老数据没有部署历史时，退回实例上挂的构建。
     */
    private Build lastDeployedBuild(Long projectId) {
        for (DeploymentHistory h : deploymentHistoryRepository.findByProjectIdOrderByDeployedAtDesc(projectId)) {
            if (!"DEPLOYED".equalsIgnoreCase(h.getStatus()) || h.getBuildId() == null) continue;
            Build b = buildRepository.findById(h.getBuildId()).orElse(null);
            if (b != null) return b;
        }
        // 兜底：按部署时间取最新的实例所挂构建
        Build best = null;
        for (ServiceInstance ins : serviceInstanceRepository.findByProjectId(projectId)) {
            if (ins.getBuildId() == null) continue;
            Build b = buildRepository.findById(ins.getBuildId()).orElse(null);
            if (b == null) continue;
            if (best == null || isNewer(b, best)) best = b;
        }
        return best;
    }

    private boolean isNewer(Build a, Build b) {
        LocalDateTime ta = a.getStartTime();
        LocalDateTime tb = b.getStartTime();
        if (ta == null) return false;
        if (tb == null) return true;
        return ta.isAfter(tb);
    }

    /**
     * 远端最新 commit。
     * <p>
     * 先用 GitHub API（能拿到提交说明与时间，浮层提示更友好）；
     * API 拿不到时退化为 {@code git ls-remote} —— 匿名 GitHub API 每小时只有 60 次，
     * 用完、或本机 hosts 把 api.github.com 指到了 127.0.0.1 时，API 会整体不可用，
     * 但 git 自己的解析不走系统 hosts，仍然问得到远端 HEAD。
     * 代价：退化路径只有 sha，没有提交说明 / 时间（时间兜底判定也就用不了）。
     */
    private GitCommit remoteHead(GitRepoInfo info, String branch, String gitUrl, String token) {
        List<GitCommit> commits = gitPlatformService.listCommits(info, branch, 1, token);
        if (commits != null && !commits.isEmpty()) return commits.get(0);

        String sha = lsRemoteHead(gitUrl, branch, token);
        if (sha == null) return null;
        log.info("[Update] GitHub API 不可用，改用 git ls-remote 取远端 HEAD ({})", branch);
        GitCommit c = new GitCommit();
        c.sha = sha;
        return c;
    }

    /** {@code git ls-remote <url> refs/heads/<branch>} → 远端该分支的 HEAD sha */
    private String lsRemoteHead(String gitUrl, String branch, String token) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", withToken(gitUrl, token), "refs/heads/" + branch);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            for (String raw : sb.toString().split("\\R")) {
                String s = raw.trim();
                if (s.isEmpty()) continue;
                String sha = s.split("\\s+")[0];
                if (sha.matches("[0-9a-fA-F]{40}")) return sha;
            }
        } catch (Exception e) {
            log.debug("[Update] git ls-remote 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 私有仓库：把 token 塞进 https 地址（与 BuildService 的做法一致，不打日志） */
    private String withToken(String gitUrl, String token) {
        if (token == null || token.isBlank()) return gitUrl;
        if (gitUrl == null || !gitUrl.startsWith("https://")) return gitUrl;
        return "https://" + token.trim() + "@" + gitUrl.substring("https://".length());
    }

    /** 分支优先级：本次部署实际用的分支 > 项目配置 > 远端默认分支 */
    private String pickBranch(Project project, Build deployed, GitRepoInfo info, String token) {
        String b = deployed.getBranch();
        if (b == null || b.isBlank()) b = project.getGitBranch();
        if (b == null || b.isBlank()) b = gitPlatformService.defaultBranch(info, token);
        if (b == null || b.isBlank()) b = "main";
        return b.trim();
    }

    /**
     * commit 优先：已部署 commit 与远端最新 commit 不一致 = 有更新。
     * 退化路径：老构建没记录 commit → 远端最新提交晚于本次部署时间 = 有更新。
     */
    private boolean hasUpdate(Build deployed, GitCommit head, boolean byCommit) {
        if (byCommit) {
            String deployedSha = deployed.getGitCommit().trim();
            String remoteSha = head.sha == null ? "" : head.sha.trim();
            if (remoteSha.isEmpty()) return false;
            return !deployedSha.equalsIgnoreCase(remoteSha);
        }
        Instant remote = parseInstant(head.date);
        Instant deployedAt = toInstant(deployed.getEndTime() != null ? deployed.getEndTime() : deployed.getStartTime());
        if (remote == null || deployedAt == null) return false;
        return remote.isAfter(deployedAt);
    }

    // ==================== 工具方法 ====================

    /** 与 GitController / CodePreviewService 同一套：项目里加密保存的 PAT，没配就是匿名 */
    private String resolveToken(Project project) {
        String enc = project.getGitToken();
        if (enc == null || enc.isBlank()) return null;
        try {
            return secretService.decrypt(enc);
        } catch (Exception e) {
            log.warn("[Update] 解密 Git Token 失败 (projectId={}): {}", project.getId(), e.getMessage());
            return null;
        }
    }

    private String trimCommit(String sha) {
        if (sha == null || sha.isBlank()) return null;
        String s = sha.trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private String firstLine(String msg) {
        if (msg == null) return "";
        int nl = msg.indexOf('\n');
        String one = nl >= 0 ? msg.substring(0, nl) : msg;
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    /** GitHub 返回 ISO-8601（如 2026-10-09T04:15:17Z） */
    private Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        for (int i = 0; i < 2; i++) {
            try {
                return i == 0 ? OffsetDateTime.parse(iso).toInstant() : Instant.parse(iso);
            } catch (Exception ignored) { /* 试下一种 */ }
        }
        try {
            return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String toIso(LocalDateTime t) {
        return t == null ? null : toInstant(t).toString();
    }
}
