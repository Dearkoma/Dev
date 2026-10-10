package com.devops.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Git 平台集成服务 — 对接 GitHub / GitLab / Gitee API
 * 支持: 分支列表、提交历史、创建 MR/PR、比较差异
 */
@Slf4j
@Service
public class GitPlatformService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 解析 Git URL，提取平台类型和 owner/repo
     */
    public GitRepoInfo parseRepoUrl(String gitUrl) {
        if (gitUrl == null) return null;
        GitRepoInfo info = new GitRepoInfo();

        // github.com/user/repo.git → github, user, repo
        if (gitUrl.contains("github.com")) {
            info.platform = "github";
            info.apiBase = "https://api.github.com";
        } else if (gitUrl.contains("gitlab.com") || gitUrl.contains("gitlab.")) {
            info.platform = "gitlab";
            // Extract base URL for self-hosted GitLab
            String base = gitUrl.substring(0, gitUrl.indexOf(".com") + 4);
            if (gitUrl.contains("gitlab.")) {
                int idx = gitUrl.indexOf("://") + 3;
                int endIdx = gitUrl.indexOf("/", idx);
                base = gitUrl.substring(0, endIdx);
            }
            info.apiBase = base + "/api/v4";
        } else if (gitUrl.contains("gitee.com")) {
            info.platform = "gitee";
            info.apiBase = "https://gitee.com/api/v5";
        } else {
            info.platform = "unknown";
            return info;
        }

        // Extract owner/repo
        String path = gitUrl;
        if (path.contains("://")) path = path.substring(path.indexOf("://") + 3);
        if (path.contains("@")) path = path.substring(path.indexOf("@") + 1);
        path = path.replace("github.com/", "").replace("gitlab.com/", "")
                .replace("gitee.com/", "").replace(".git", "").trim();
        String[] parts = path.split("/");
        if (parts.length >= 2) {
            info.owner = parts[0];
            info.repo = parts[1];
        }

        return info;
    }

    /**
     * 获取分支列表
     */
    public List<GitBranch> listBranches(GitRepoInfo info, String token) {
        List<GitBranch> branches = new ArrayList<>();
        String url = getBranchesUrl(info);
        JsonNode result = apiGet(url, token);
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                GitBranch b = new GitBranch();
                b.name = node.has("name") ? node.get("name").asText() : "";
                if (node.has("commit") && node.get("commit").has("sha")) {
                    b.lastCommitSha = node.get("commit").get("sha").asText();
                }
                if (node.has("protected")) {
                    b.isProtected = node.get("protected").asBoolean();
                }
                if (node.has("commit") && node.get("commit").has("committed_date")) {
                    b.lastCommitDate = node.get("commit").get("committed_date").asText();
                }
                branches.add(b);
            }
        }
        return branches;
    }

    /**
     * 获取提交历史
     */
    public List<GitCommit> listCommits(GitRepoInfo info, String branch, int limit, String token) {
        List<GitCommit> commits = new ArrayList<>();
        String url = getCommitsUrl(info, branch) + "&per_page=" + limit;
        JsonNode result = apiGet(url, token);
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                GitCommit c = new GitCommit();
                c.sha = getText(node, "sha");
                if (node.has("commit")) {
                    JsonNode commit = node.get("commit");
                    c.message = getText(commit, "message");
                    if (commit.has("author")) {
                        c.author = getText(commit.get("author"), "name");
                        c.date = getText(commit.get("author"), "date");
                    }
                }
                if (node.has("html_url")) c.htmlUrl = getText(node, "html_url");
                commits.add(c);
            }
        }
        return commits;
    }

    /**
     * 创建 Merge Request / Pull Request
     */
    public GitMR createMR(GitRepoInfo info, String title, String sourceBranch,
                          String targetBranch, String description, String token) {
        String url = getMRCreateUrl(info);
        Map<String, Object> body = new LinkedHashMap<>();
        if ("github".equals(info.platform)) {
            body.put("title", title);
            body.put("head", sourceBranch);
            body.put("base", targetBranch);
            body.put("body", description != null ? description : "");
        } else if ("gitlab".equals(info.platform)) {
            body.put("title", title);
            body.put("source_branch", sourceBranch);
            body.put("target_branch", targetBranch);
            body.put("description", description != null ? description : "");
        } else if ("gitee".equals(info.platform)) {
            body.put("title", title);
            body.put("head", sourceBranch);
            body.put("base", targetBranch);
            body.put("body", description != null ? description : "");
        }
        JsonNode result = apiPost(url, body, token);
        if (result != null) {
            GitMR mr = new GitMR();
            mr.id = result.has("id") ? result.get("id").asInt() : (result.has("number") ? result.get("number").asInt() : 0);
            mr.title = getText(result, "title");
            mr.url = getText(result, "html_url");
            mr.sourceBranch = sourceBranch;
            mr.targetBranch = targetBranch;
            mr.state = getText(result, "state");
            return mr;
        }
        return null;
    }

    /**
     * 获取 MR/PR 列表
     */
    public List<GitMR> listMRs(GitRepoInfo info, String state, String token) {
        List<GitMR> mrs = new ArrayList<>();
        String url = getMRListUrl(info, state);
        JsonNode result = apiGet(url, token);
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                GitMR mr = new GitMR();
                mr.id = node.has("iid") ? node.get("iid").asInt() : (node.has("number") ? node.get("number").asInt() : 0);
                mr.title = getText(node, "title");
                mr.url = getText(node, "html_url");
                mr.state = getText(node, "state");
                if (node.has("source_branch")) {
                    mr.sourceBranch = node.get("source_branch").asText();
                } else if (node.has("head")) {
                    mr.sourceBranch = node.get("head").has("ref") ? node.get("head").get("ref").asText() : "";
                }
                if (node.has("target_branch")) {
                    mr.targetBranch = node.get("target_branch").asText();
                } else if (node.has("base")) {
                    mr.targetBranch = node.get("base").has("ref") ? node.get("base").get("ref").asText() : "";
                }
                mrs.add(mr);
            }
        }
        return mrs;
    }

    // ===================== GitHub Actions / Release =====================

    /**
     * 取仓库的默认分支（GitHub 的 default_branch 字段）。
     * 用于在用户未指定分支时给出正确的 ref —— 很多仓库默认是 master，写死 main 会 404。
     */
    public String defaultBranch(GitRepoInfo info, String token) {
        if (info == null || !"github".equals(info.platform)) return null;
        JsonNode node = apiGet(info.apiBase + "/repos/" + info.owner + "/" + info.repo, token);
        if (node != null && node.has("default_branch") && !node.get("default_branch").isNull()) {
            return node.get("default_branch").asText();
        }
        return null;
    }

    /**
     * 触发 workflow_dispatch（手动触发云端工作流）。
     * @return null 表示触发成功；否则返回可直接展示给用户的错误说明
     */
    public String dispatchWorkflow(GitRepoInfo info, String workflowFile, String ref, String token) {
        if (info == null || !"github".equals(info.platform)) {
            return "仅支持 GitHub Actions（当前仓库平台: " + (info == null ? "未知" : info.platform) + "）";
        }
        String wf = (workflowFile == null || workflowFile.isBlank()) ? "build.yaml" : workflowFile.trim();
        String r = (ref == null || ref.isBlank()) ? "main" : ref.trim();
        String url = info.apiBase + "/repos/" + info.owner + "/" + info.repo
                + "/actions/workflows/" + wf + "/dispatches";
        HttpResponse<String> resp = postRaw(url, Map.of("ref", r), token);
        if (resp == null) return "调用 GitHub API 失败（网络异常或代理不可达）";
        int code = resp.statusCode();
        if (code == 204) return null;   // 成功（无响应体）
        if (code == 404) return "未找到 workflow「" + wf + "」（HTTP 404）：确认文件名正确，且该仓库对当前 Token 可见";
        if (code == 401) return "GitHub 鉴权失败（HTTP 401）：PAT 无效或已过期";
        if (code == 403) return "权限不足（HTTP 403）：PAT 需要 repo + workflow（actions:write）权限";
        if (code == 422) return "触发被拒绝（HTTP 422）：该 workflow 很可能没有 workflow_dispatch 触发器，或分支「" + r + "」不存在";
        return "触发失败 (HTTP " + code + "): " + truncate(resp.body(), 200);
    }

    /** 查询最近的工作流运行记录 */
    public List<WorkflowRun> listWorkflowRuns(GitRepoInfo info, String workflowFile, int limit, String token) {
        List<WorkflowRun> runs = new ArrayList<>();
        if (info == null || !"github".equals(info.platform)) return runs;
        int n = limit <= 0 ? 10 : Math.min(limit, 50);
        String base = info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/actions";
        String url = (workflowFile == null || workflowFile.isBlank())
                ? base + "/runs?per_page=" + n
                : base + "/workflows/" + workflowFile.trim() + "/runs?per_page=" + n;
        JsonNode result = apiGet(url, token);
        if (result != null && result.has("workflow_runs") && result.get("workflow_runs").isArray()) {
            for (JsonNode node : result.get("workflow_runs")) {
                WorkflowRun r = new WorkflowRun();
                r.id = node.has("id") ? node.get("id").asLong() : 0L;
                r.name = getText(node, "name");
                r.displayTitle = getText(node, "display_title");
                r.branch = getText(node, "head_branch");
                r.headSha = getText(node, "head_sha");
                r.event = getText(node, "event");
                r.status = getText(node, "status");         // queued / in_progress / completed
                r.conclusion = getText(node, "conclusion"); // success / failure / cancelled / ""
                r.htmlUrl = getText(node, "html_url");
                r.createdAt = getText(node, "created_at");
                r.updatedAt = getText(node, "updated_at");
                runs.add(r);
            }
        }
        return runs;
    }

    /** 查询某次运行包含的 job 与步骤 */
    public List<Job> listRunJobs(GitRepoInfo info, long runId, String token) {
        List<Job> jobs = new ArrayList<>();
        if (info == null || !"github".equals(info.platform)) return jobs;
        String url = info.apiBase + "/repos/" + info.owner + "/" + info.repo
                + "/actions/runs/" + runId + "/jobs";
        JsonNode result = apiGet(url, token);
        if (result != null && result.has("jobs") && result.get("jobs").isArray()) {
            for (JsonNode node : result.get("jobs")) {
                Job j = new Job();
                j.id = node.has("id") ? node.get("id").asLong() : 0L;
                j.name = getText(node, "name");
                j.status = getText(node, "status");
                j.conclusion = getText(node, "conclusion");
                j.startedAt = getText(node, "started_at");
                j.completedAt = getText(node, "completed_at");
                j.htmlUrl = getText(node, "html_url");
                if (node.has("steps") && node.get("steps").isArray()) {
                    for (JsonNode s : node.get("steps")) {
                        Step st = new Step();
                        st.number = s.has("number") ? s.get("number").asInt() : 0;
                        st.name = getText(s, "name");
                        st.status = getText(s, "status");
                        st.conclusion = getText(s, "conclusion");
                        j.steps.add(st);
                    }
                }
                jobs.add(j);
            }
        }
        return jobs;
    }

    /**
     * 在指定分支的最新提交上创建 tag。
     * 打好 tag 后，仓库里「tag 触发 → build → 发 Release」的 workflow 会自动跑完发布流程，
     * 所以 D 平台只需负责「打 tag」这一步，构建与发布都在 GitHub Actions 上完成。
     * @return null 表示创建成功；否则返回错误说明
     */
    public String createTag(GitRepoInfo info, String tag, String ref, String token) {
        if (info == null || !"github".equals(info.platform)) {
            return "仅支持 GitHub（当前仓库平台: " + (info == null ? "未知" : info.platform) + "）";
        }
        if (tag == null || tag.isBlank()) return "tag 不能为空";
        String t = tag.trim();
        if (!t.matches("[A-Za-z0-9._/-]+")) return "tag 只能包含字母、数字、点、下划线、短横线和斜杠";
        String r = (ref == null || ref.isBlank()) ? "main" : ref.trim();

        // 1) 取目标分支最新 commit 的 SHA
        String refUrl = info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/git/ref/heads/" + r;
        HttpResponse<String> refResp = getRaw(refUrl, token);
        if (refResp == null) return "调用 GitHub API 失败（网络异常或代理不可达）";
        if (refResp.statusCode() == 404) return "取不到分支「" + r + "」：分支不存在，或 Token 无仓库读取权限";
        if (refResp.statusCode() == 401 || refResp.statusCode() == 403) {
            return "GitHub 鉴权失败（HTTP " + refResp.statusCode() + "）：请检查 PAT 权限";
        }
        String sha = null;
        try {
            JsonNode node = mapper.readTree(refResp.body());
            if (node.has("object") && node.get("object").has("sha")) {
                sha = node.get("object").get("sha").asText();
            }
        } catch (Exception e) {
            log.warn("解析分支 SHA 失败: {}", e.getMessage());
        }
        if (sha == null) return "分支「" + r + "」的提交 SHA 解析失败";

        // 2) 创建 refs/tags/<tag> 指向该 SHA
        String url = info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/git/refs";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", "refs/tags/" + t);
        body.put("sha", sha);
        HttpResponse<String> resp = postRaw(url, body, token);
        if (resp == null) return "调用 GitHub API 失败（网络异常或代理不可达）";
        int code = resp.statusCode();
        if (code == 201) return null;   // 创建成功
        if (code == 422) return "tag「" + t + "」已存在（GitHub 不允许重复 tag）";
        if (code == 401 || code == 403) return "权限不足（HTTP " + code + "）：PAT 需要 repo（contents:write）权限";
        return "创建 tag 失败 (HTTP " + code + "): " + truncate(resp.body(), 200);
    }

    // ===================== URL 构建 =====================

    private String getBranchesUrl(GitRepoInfo info) {
        if ("github".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/branches?per_page=50";
        } else if ("gitlab".equals(info.platform)) {
            return info.apiBase + "/projects/" + urlEncode(info.owner + "/" + info.repo) + "/repository/branches?per_page=50";
        } else if ("gitee".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/branches?per_page=50";
        }
        return "";
    }

    private String getCommitsUrl(GitRepoInfo info, String branch) {
        String b = branch != null ? branch : "main";
        if ("github".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/commits?sha=" + b;
        } else if ("gitlab".equals(info.platform)) {
            return info.apiBase + "/projects/" + urlEncode(info.owner + "/" + info.repo) + "/repository/commits?ref_name=" + b;
        } else if ("gitee".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/commits?sha=" + b;
        }
        return "";
    }

    private String getMRCreateUrl(GitRepoInfo info) {
        if ("github".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/pulls";
        } else if ("gitlab".equals(info.platform)) {
            return info.apiBase + "/projects/" + urlEncode(info.owner + "/" + info.repo) + "/merge_requests";
        } else if ("gitee".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/pulls";
        }
        return "";
    }

    private String getMRListUrl(GitRepoInfo info, String state) {
        String s = state != null ? state : "open";
        if ("github".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/pulls?state=" + s;
        } else if ("gitlab".equals(info.platform)) {
            return info.apiBase + "/projects/" + urlEncode(info.owner + "/" + info.repo) + "/merge_requests?state=" + s;
        } else if ("gitee".equals(info.platform)) {
            return info.apiBase + "/repos/" + info.owner + "/" + info.repo + "/pulls?state=" + s;
        }
        return "";
    }

    // ===================== HTTP 工具 =====================

    /**
     * 执行 GET 并返回原始响应（含状态码）。
     * 与 apiGet 的区别：apiGet 只关心 body 且把非 2xx 当失败，
     * 而调用方需要区分 401/403/404/422 才能给出可操作的提示。
     * token 为空时不发送 Authorization 头（空 Bearer 会被 GitHub 判为无效凭证）。
     */
    private HttpResponse<String> getRaw(String url, String token) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            String auth = token(token);
            if (!auth.isBlank()) builder.header("Authorization", auth);
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Git API GET 失败 {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 执行 POST 并返回原始响应（含状态码）。
     * 用于 workflow dispatch 这类「成功返回 204 无内容」的接口 ——
     * 用返回 JsonNode 的 apiPost 会把空 body 解析异常吞掉，导致成功被误判为失败。
     */
    private HttpResponse<String> postRaw(String url, Object body, String token) {
        try {
            String jsonBody = mapper.writeValueAsString(body == null ? Map.of() : body);
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            String auth = token(token);
            if (!auth.isBlank()) builder.header("Authorization", auth);
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Git API POST 失败 {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private JsonNode apiGet(String url, String token) {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("Authorization", token(token))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return mapper.readTree(resp.body());
            } else {
                log.warn("Git API GET {} → HTTP {}: {}", url, resp.statusCode(), resp.body().substring(0, Math.min(200, resp.body().length())));
            }
        } catch (Exception e) {
            log.warn("Git API GET 失败 {}: {}", url, e.getMessage());
        }
        return null;
    }

    private JsonNode apiPost(String url, Map<String, Object> body, String token) {
        try {
            String jsonBody = mapper.writeValueAsString(body);
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", token(token))
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return mapper.readTree(resp.body());
            } else {
                log.warn("Git API POST {} → HTTP {}: {}", url, resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("Git API POST 失败 {}: {}", url, e.getMessage());
        }
        return null;
    }

    private String token(String t) {
        if (t == null || t.isBlank()) return "";
        if (t.startsWith("Bearer ") || t.startsWith("token ") || t.startsWith("Basic ")) return t;
        return "Bearer " + t;
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String getText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }

    // ===================== DTO 类 =====================

    public static class GitRepoInfo {
        public String platform;
        public String apiBase;
        public String owner;
        public String repo;
    }

    public static class GitBranch {
        public String name;
        public String lastCommitSha;
        public String lastCommitDate;
        public boolean isProtected;
    }

    public static class GitCommit {
        public String sha;
        public String message;
        public String author;
        public String date;
        public String htmlUrl;
    }

    public static class GitMR {
        public int id;
        public String title;
        public String url;
        public String sourceBranch;
        public String targetBranch;
        public String state;
    }

    /** GitHub Actions 一次运行 */
    public static class WorkflowRun {
        public long id;
        public String name;
        public String displayTitle;
        public String branch;
        public String headSha;
        public String event;        // push / workflow_dispatch / ...
        public String status;       // queued / in_progress / completed
        public String conclusion;   // success / failure / cancelled / "" (进行中为空)
        public String htmlUrl;
        public String createdAt;
        public String updatedAt;
    }

    /** 运行内的一个 job */
    public static class Job {
        public long id;
        public String name;
        public String status;
        public String conclusion;
        public String startedAt;
        public String completedAt;
        public String htmlUrl;
        public List<Step> steps = new ArrayList<>();
    }

    /** job 内的一个步骤 */
    public static class Step {
        public int number;
        public String name;
        public String status;
        public String conclusion;
    }
}
