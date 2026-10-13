package com.devops.platform.service;

import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * CodePreviewService 远程预览测试 —— 真实访问 GitHub API（不依赖本地工作目录）。
 * <p>
 * 背景：文件内容原先走 raw.githubusercontent.com 获取，部分网络环境下该域名 TLS
 * 握手会被直接重置，前端表现为「/* 读取失败: Remote host terminated the handshake *&#47;」。
 * 现已改为走 contents API（api.github.com / gitee.com/api/v5），本测试锁住这个行为。
 * <p>
 * 公开仓库的用例要求「未配置任何凭证」也能读取，对应产品约定：
 * 查看代码 / 分支 / 提交 / Actions 无需登录，登录只影响「触发构建」「打 tag 发布」。
 * 另外覆盖私有仓库场景：无凭证时要把「去配 PAT」说清楚，凭证无效时不能整条链路崩掉。
 * <p>
 * 需要外网。api.github.com 不可达时整体跳过（不会误报失败）。
 */
class CodePreviewServiceTest {

    private static final String GIT_URL = "https://github.com/Dearkoma/AudioPlaybackConnector";
    private static final String BRANCH = "master";
    private static final long PROJECT_ID = 999001L;

    private static final SecretService SECRETS = new SecretService("");

    private CodePreviewService service;

    @BeforeAll
    static void preflight() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/Dearkoma/AudioPlaybackConnector"))
                    .header("User-Agent", "DevOps-Platform")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<Void> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.discarding());
            Assumptions.assumeTrue(resp.statusCode() == 200,
                    "api.github.com 返回 HTTP " + resp.statusCode() + "，跳过远程预览测试");
        } catch (Exception e) {
            Assumptions.abort("无法访问 api.github.com（" + e.getMessage() + "），跳过远程预览测试");
        }
    }

    @BeforeEach
    void setUp() {
        service = newService(GIT_URL, null);
    }

    /**
     * 造一个已 mock 好仓库的 service。
     * @param gitUrl  仓库地址；null 用默认的公开仓库
     * @param encToken 项目里加密保存的凭证；null / 空 = 未配置
     */
    private CodePreviewService newService(String gitUrl, String encToken) {
        ProjectRepository repo = Mockito.mock(ProjectRepository.class);
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setName("AudioPlaybackConnector");
        p.setCode("apc-test");
        p.setGitUrl(gitUrl != null ? gitUrl : GIT_URL);
        p.setGitBranch(BRANCH);
        p.setGitToken(encToken);
        when(repo.findById(PROJECT_ID)).thenReturn(Optional.of(p));
        return new CodePreviewService(repo, SECRETS);
    }

    /** 文件树：无凭证也应能取到（api.github.com） */
    @Test
    void treeShouldLoadWithoutToken() {
        Map<String, Object> res = service.getFileTree(PROJECT_ID);
        assertNull(res.get("error"), "文件树不应报错：" + res.get("error"));

        Object tree = res.get("tree");
        assertInstanceOf(Map.class, tree);
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) ((Map<String, Object>) tree).get("children");
        assertNotNull(children);
        assertFalse(children.isEmpty(), "仓库非空，文件树应有节点");
    }

    /** 单文件：走 contents API + base64 解码，取到真实内容 */
    @Test
    void fileContentShouldBeReadViaContentsApi() {
        Map<String, Object> res = service.getFileContent(PROJECT_ID, ".github/workflows/build.yaml");
        assertNull(res.get("error"), "读取文件不应报错：" + res.get("error"));

        String content = String.valueOf(res.get("content"));
        assertTrue(content.contains("name:"), "应取到真实 YAML 内容，实际：" + content);
        assertEquals("build.yaml", res.get("name"));
        assertEquals(".github/workflows/build.yaml", res.get("path"));
        assertNotNull(res.get("size"));
    }

    /** 不存在的文件 → 明确报错，而不是抛异常 */
    @Test
    void missingFileShouldReturnError() {
        Map<String, Object> res = service.getFileContent(PROJECT_ID, "no/such/file-xyz-not-exists.txt");
        assertNotNull(res.get("error"), "不存在的文件应返回 error");
    }

    /** 目标是目录 → 明确报错（contents API 返回数组） */
    @Test
    void directoryPathShouldReturnError() {
        Map<String, Object> res = service.getFileContent(PROJECT_ID, ".github");
        assertNotNull(res.get("error"), "目录路径应返回 error");
    }

    /**
     * 私有仓库 / 不存在的仓库：GitHub 一律返回 404。
     * 未配置凭证时必须明确引导用户去配 PAT，否则用户只看到一句「不存在」根本无从下手。
     */
    @Test
    void privateRepoWithoutTokenShouldHintAtCredential() {
        CodePreviewService s = newService("https://github.com/Dearkoma/no-such-repo-xyz-9527", null);
        Map<String, Object> res = s.getFileTree(PROJECT_ID);
        String err = String.valueOf(res.get("error"));
        assertNotNull(res.get("error"), "读不到仓库应返回 error");
        assertTrue(err.contains("私有仓库") && err.contains("Personal Access Token"),
                "无凭证 404 应引导去配置 PAT，实际：" + err);
    }

    /** 凭证无效（401）→ 提示凭证问题，而不是抛出异常或含糊的「读取失败」 */
    @Test
    void invalidTokenShouldReportAuthFailure() {
        CodePreviewService s = newService(GIT_URL, SECRETS.encrypt("ghp_thisTokenIsDefinitelyInvalid000000"));
        Map<String, Object> res = s.getFileTree(PROJECT_ID);
        String err = String.valueOf(res.get("error"));
        assertNotNull(res.get("error"), "无效凭证应返回 error");
        assertTrue(err.contains("401") || err.contains("凭证"), "应提示凭证无效，实际：" + err);
    }

    /** 未配置凭证时不能发空 Authorization —— 否则公开仓库也会被 GitHub 判 401 */
    @Test
    void blankTokenShouldNotBreakPublicRepo() {
        Map<String, Object> res = newService(GIT_URL, "   ").getFileTree(PROJECT_ID);
        assertNull(res.get("error"), "空凭证下公开仓库仍应可读：" + res.get("error"));
    }
}
