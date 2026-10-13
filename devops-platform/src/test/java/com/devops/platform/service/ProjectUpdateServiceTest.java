package com.devops.platform.service;

import com.devops.platform.entity.Build;
import com.devops.platform.entity.DeploymentHistory;
import com.devops.platform.entity.Project;
import com.devops.platform.repository.BuildRepository;
import com.devops.platform.repository.DeploymentHistoryRepository;
import com.devops.platform.repository.ServiceInstanceRepository;
import com.devops.platform.service.GitPlatformService.GitCommit;
import com.devops.platform.service.GitPlatformService.GitRepoInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 「已部署的项目在远端是否有更新」判定口径测试。
 * <p>
 * 全部离线：Git 平台用 mock，不真的打 GitHub。锁住两条判定路径 ——
 * commit 比对（准）与提交时间兜底（老构建没记录 commit 时用）。
 */
class ProjectUpdateServiceTest {

    private static final long PROJECT_ID = 880001L;
    private static final String DEPLOYED_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String REMOTE_SHA   = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private DeploymentHistoryRepository historyRepo;
    private ServiceInstanceRepository instanceRepo;
    private BuildRepository buildRepo;
    private GitPlatformService gitService;
    private ProjectUpdateService service;

    @BeforeEach
    void setUp() {
        historyRepo = Mockito.mock(DeploymentHistoryRepository.class);
        instanceRepo = Mockito.mock(ServiceInstanceRepository.class);
        buildRepo = Mockito.mock(BuildRepository.class);
        gitService = Mockito.mock(GitPlatformService.class);
        service = new ProjectUpdateService(historyRepo, instanceRepo, buildRepo, gitService, new SecretService(""));

        GitRepoInfo info = new GitRepoInfo();
        info.platform = "github";
        info.apiBase = "https://api.github.com";
        info.owner = "Dearkoma";
        info.repo = "AudioPlaybackConnector";
        when(gitService.parseRepoUrl(anyString())).thenReturn(info);
        when(instanceRepo.findByProjectId(anyLong())).thenReturn(Collections.emptyList());
    }

    private Project project() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setName("AudioPlaybackConnector");
        p.setCode("apc");
        p.setGitUrl("https://github.com/Dearkoma/AudioPlaybackConnector");
        p.setGitBranch("master");
        return p;
    }

    /** 造一个「已部署」的构建，写入部署历史 */
    private Build deployedBuild(String gitCommit, LocalDateTime endTime) {
        Build b = new Build();
        b.setId(1L);
        b.setProjectId(PROJECT_ID);
        b.setBuildNumber("#7");
        b.setStatus("SUCCESS");
        b.setBranch("master");
        b.setDbName("devops_apc");
        b.setPipelineId(3L);
        b.setStartTime(endTime.minusMinutes(2));
        b.setEndTime(endTime);
        b.setGitCommit(gitCommit);

        DeploymentHistory h = new DeploymentHistory();
        h.setId(9L);
        h.setProjectId(PROJECT_ID);
        h.setBuildId(1L);
        h.setStatus("DEPLOYED");
        h.setDeployedAt(endTime);
        when(historyRepo.findByProjectIdOrderByDeployedAtDesc(PROJECT_ID)).thenReturn(List.of(h));
        when(buildRepo.findById(1L)).thenReturn(Optional.of(b));
        return b;
    }

    private void remoteHead(String sha, String isoDate, String message) {
        GitCommit c = new GitCommit();
        c.sha = sha;
        c.date = isoDate;
        c.message = message;
        c.author = "Dearkoma";
        when(gitService.listCommits(any(), anyString(), anyInt(), any())).thenReturn(List.of(c));
    }

    private Map<String, Object> checkOne() {
        List<Map<String, Object>> all = service.checkAll(List.of(project()), true);
        assertEquals(1, all.size());
        return all.get(0);
    }

    /** 没部署过 → 不提示任何东西 */
    @Test
    void notDeployedShouldNotReportUpdate() {
        when(historyRepo.findByProjectIdOrderByDeployedAtDesc(PROJECT_ID)).thenReturn(Collections.emptyList());
        Map<String, Object> r = checkOne();
        assertEquals(Boolean.FALSE, r.get("deployed"));
        assertEquals(Boolean.FALSE, r.get("hasUpdate"));
    }

    /** commit 比对：远端还是同一个 commit → 无更新 */
    @Test
    void sameCommitMeansNoUpdate() {
        deployedBuild(DEPLOYED_SHA, LocalDateTime.now().minusHours(2));
        remoteHead(DEPLOYED_SHA, "2026-10-09T02:00:00Z", "旧提交");
        Map<String, Object> r = checkOne();
        assertEquals(Boolean.TRUE, r.get("deployed"));
        assertEquals(Boolean.FALSE, r.get("hasUpdate"));
        assertEquals("COMMIT", r.get("basis"));
    }

    /** commit 比对：远端有更新的 commit → 有更新，并带上提交说明与流水线、库名供一键更新复用 */
    @Test
    void differentCommitMeansUpdate() {
        deployedBuild(DEPLOYED_SHA, LocalDateTime.now().minusHours(2));
        remoteHead(REMOTE_SHA, "2026-10-09T05:00:00Z", "fix: 修个 bug");
        Map<String, Object> r = checkOne();
        assertEquals(Boolean.TRUE, r.get("hasUpdate"));
        assertEquals("fix: 修个 bug", r.get("remoteMessage"));
        assertEquals(3L, r.get("pipelineId"));
        assertEquals("devops_apc", r.get("dbName"));
    }

    /** 老构建没记录 commit → 退化为时间判断：远端提交晚于部署时间 = 有更新 */
    @Test
    void legacyBuildFallsBackToTimeAndDetectsUpdate() {
        deployedBuild(null, LocalDateTime.now().minusHours(3));
        // 部署于 3 小时前，远端提交换成 1 小时前
        String recent = LocalDateTime.now().minusHours(1).atZone(ZoneId.systemDefault())
                .toOffsetDateTime().toString();
        remoteHead(REMOTE_SHA, recent, "feat: 新功能");
        Map<String, Object> r = checkOne();
        assertEquals("TIME", r.get("basis"));
        assertEquals(Boolean.TRUE, r.get("hasUpdate"));
    }

    /** 老构建没记录 commit 且远端提交更早 → 不该误报有更新 */
    @Test
    void legacyBuildWithOlderRemoteCommitMeansNoUpdate() {
        deployedBuild(null, LocalDateTime.now().minusHours(1));
        String older = LocalDateTime.now().minusHours(5).atZone(ZoneId.systemDefault())
                .toOffsetDateTime().toString();
        remoteHead(DEPLOYED_SHA, older, "旧提交");
        Map<String, Object> r = checkOne();
        assertEquals("TIME", r.get("basis"));
        assertEquals(Boolean.FALSE, r.get("hasUpdate"));
    }

    /**
     * GitHub API 不可用（限流 / 被 hosts 屏蔽）→ 退化为 git ls-remote，仍能判断更新。
     * 这里用一个本地 git 仓库当远端，完全离线，顺便验证 ls-remote 的输出解析。
     */
    @Test
    void fallsBackToGitLsRemoteWhenApiIsUnavailable() throws Exception {
        Path repo = Files.createTempDirectory("update-check-remote");
        try {
            run(repo, "git", "init", "-b", "master");
            run(repo, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "--allow-empty", "-m", "init");
            String sha = run(repo, "git", "rev-parse", "HEAD").trim();

            // API 拿不到 → 走 ls-remote
            when(gitService.listCommits(any(), anyString(), anyInt(), any())).thenReturn(Collections.emptyList());
            deployedBuild(sha, LocalDateTime.now().minusMinutes(5));

            Project p = project();
            p.setGitUrl(repo.toString().replace('\\', '/'));
            Map<String, Object> r = service.checkAll(List.of(p), true).get(0);

            assertEquals(sha, r.get("remoteCommit"), "ls-remote 应解析出远端 HEAD");
            assertEquals(Boolean.FALSE, r.get("hasUpdate"), "commit 相同 → 无更新");
            assertNull(r.get("remoteDate"), "退化路径没有提交时间");
        } finally {
            deleteRecursively(repo);
        }
    }

    private static String run(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "命令超时: " + String.join(" ", cmd));
        assertEquals(0, p.exitValue(), "命令失败: " + String.join(" ", cmd) + "\n" + out);
        return out;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) { /* 清理失败不影响测试结论 */ }
            });
        }
    }

    /** 未配置 Git 地址 → 不报错，只是给个说明 */
    @Test
    void projectWithoutGitUrlShouldExplainItself() {
        deployedBuild(DEPLOYED_SHA, LocalDateTime.now().minusHours(2));
        Project p = project();
        p.setGitUrl("");
        List<Map<String, Object>> all = service.checkAll(List.of(p), true);
        Map<String, Object> r = all.get(0);
        assertEquals(Boolean.TRUE, r.get("deployed"));
        assertEquals(Boolean.FALSE, r.get("hasUpdate"));
        assertNotNull(r.get("note"));
    }
}
