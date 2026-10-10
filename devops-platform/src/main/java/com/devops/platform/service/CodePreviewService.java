package com.devops.platform.service;

import com.devops.platform.entity.Project;
import com.devops.platform.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * 代码预览服务 —— 纯远程，不 clone、不构建、不依赖本地工作目录。
 * 支持 GitHub / Gitee API 直读，通用 Git 走临时浅克隆。
 * <p>
 * 凭证：公开仓库无需任何凭证；私有仓库需要 PAT。取用顺序与 {@code GitController} 一致 ——
 * 请求头 {@code X-Git-Token} 优先，其次项目里加密保存的 {@code Project.gitToken}（AES-GCM）。
 * 前端不需要额外传参：在「Git 集成 → GitHub 凭证」里存过一次，这里就能读私有仓库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodePreviewService {

    private final ProjectRepository projectRepository;
    private final SecretService secretService;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITEE_API_BASE = "https://gitee.com/api/v5";

    // ---- 临时克隆缓存（仅非 GitHub/Gitee 仓库使用） ----
    private final Map<Long, TempClone> cloneCache = new ConcurrentHashMap<>();
    private static final long CLONE_TTL_MS = 30 * 60 * 1000; // 30 分钟

    /**
     * 分支解析缓存。Gitee 匿名调用按 IP 限流 **60 次/小时**，而 {@code resolveBranch}
     * 每次最多要打 2 个接口；不做缓存的话「预览一次 = 3 次调用、点开一个文件 = 3 次调用」，
     * 点十几个文件就把整点额度烧光了。缓存后：预览一次只花 1 次调用。
     * key = apiBase|owner/repo|配置分支
     */
    private final Map<String, BranchCache> branchCache = new ConcurrentHashMap<>();
    private static final long BRANCH_TTL_MS = 30 * 60 * 1000; // 30 分钟

    private static final int MAX_FILE_SIZE = 500 * 1024;

    /** 平台 API 配额（用于提示文案）：Gitee 未认证 60 次/小时/IP，认证后 5000 次/小时 */
    private static final String QUOTA_ANON = "60";
    private static final String QUOTA_AUTH = "5000";

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "__pycache__",
            ".idea", ".vscode", "dist", "build", ".gradle", "vendor", ".mvn");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "jar", "war", "class", "exe", "dll", "so", "dylib", "bin",
            "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp",
            "pdf", "zip", "gz", "tar", "7z", "rar",
            "mp3", "mp4", "avi", "mov", "wmv", "flv",
            "ttf", "otf", "woff", "woff2", "eot",
            "db", "sqlite", "sqlite3");

    // ==================== 文件树 ====================

    public Map<String, Object> getFileTree(Long projectId) {
        return getFileTree(projectId, null);
    }

    /** @param headerToken 可为空；为空则回退到项目里加密保存的凭证（公开仓库两者都为空即可） */
    public Map<String, Object> getFileTree(Long projectId, String headerToken) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        String gitUrl = project.getGitUrl();
        if (gitUrl == null || gitUrl.isBlank()) {
            return error("项目未配置 Git 地址");
        }

        GitInfo info = parseGitUrl(gitUrl, project.getGitBranch());
        if (info == null) {
            return error("无法解析 Git 地址格式: " + gitUrl);
        }

        String token = resolveToken(project, headerToken);

        try {
            return switch (info.platform) {
                case "github" -> buildTreeFromGitHub(info, GITHUB_API_BASE, token);
                case "gitee"  -> buildGiteeTree(info, token);
                default       -> buildTreeFromTempClone(projectId, info);
            };
        } catch (Exception e) {
            log.error("远程预览失败 projectId={} url={}", projectId, gitUrl, e);
            return error("预览失败: " + e.getMessage());
        }
    }

    // ==================== 文件内容 ====================

    public Map<String, Object> getFileContent(Long projectId, String filePath) {
        return getFileContent(projectId, filePath, null);
    }

    /** @param headerToken 可为空；为空则回退到项目里加密保存的凭证 */
    public Map<String, Object> getFileContent(Long projectId, String filePath, String headerToken) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("项目不存在: " + projectId));

        String gitUrl = project.getGitUrl();
        if (gitUrl == null || gitUrl.isBlank()) {
            return error("项目未配置 Git 地址");
        }

        GitInfo info = parseGitUrl(gitUrl, project.getGitBranch());
        if (info == null) {
            return error("无法解析 Git 地址格式");
        }

        String name = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        if (isBinaryByName(name)) {
            return error("二进制文件无法预览");
        }

        String token = resolveToken(project, headerToken);

        try {
            return switch (info.platform) {
                case "github" -> fetchFileViaApi(info, filePath, GITHUB_API_BASE, token);
                case "gitee"  -> fetchGiteeFile(info, filePath, token);
                default -> fetchFileFromClone(projectId, filePath);
            };
        } catch (Exception e) {
            log.error("读取远程文件失败 projectId={} path={}", projectId, filePath, e);
            return error("读取失败: " + e.getMessage());
        }
    }

    // ==================== GitHub / Gitee 共用 API 树构建 ====================

    private Map<String, Object> buildTreeFromGitHub(GitInfo info, String apiBase, String token) throws Exception {
        // 获取仓库默认分支（如果指定分支不存在）
        String branch = resolveBranch(apiBase, info, token);

        String url = apiBase + "/repos/" + info.owner + "/" + info.repo
                + "/git/trees/" + branch + "?recursive=1";
        HttpResponse<String> resp = sendGet(url, token);
        if (resp == null) return error("网络异常，无法访问 " + platformOf(url) + " API（请检查本机网络或代理设置）");

        int code = resp.statusCode();
        // GitHub 对空仓库返回 409，视为「没有文件」而不是错误
        if (code == 409) return successTree(info.repo, List.of());
        if (code != 200) return httpError(url, code, resp.body(), token);

        JsonNode body = om.readTree(resp.body());
        JsonNode tree = body.get("tree");
        if (tree == null || !tree.isArray() || tree.size() == 0) {
            return successTree(info.repo, List.of());
        }

        // 展平 → 嵌套
        TreeNode rootNode = new TreeNode(info.repo, "directory", "");
        for (JsonNode item : tree) {
            String path = item.get("path").asText();
            String type = "tree".equals(item.get("type").asText()) ? "directory" : "file";
            long size = item.has("size") ? item.get("size").asLong() : 0;

            String[] parts = path.split("/");
            if (shouldSkip(parts)) continue;

            if ("file".equals(type) && isBinaryByName(parts[parts.length - 1])) {
                type = "binary";
            }

            rootNode.add(parts, 0, type, path, size);
        }

        rootNode.sort();
        return successTree(info.repo, rootNode.childrenAsList());
    }

    // ==================== Gitee 文件树（API 优先，限流时走网页接口兜底） ====================

    /**
     * Gitee 文件树。匿名调用 {@code /api/v5} 只有 60 次/小时，额度一满连公开仓库都读不了，
     * 所以 API 拿不到时自动回落到网页端 file_list（不吃该配额）。
     */
    private Map<String, Object> buildGiteeTree(GitInfo info, String token) throws Exception {
        Map<String, Object> api = buildTreeFromGitHub(info, GITEE_API_BASE, token);
        if (!api.containsKey("error")) return api;

        Map<String, Object> fallback = buildTreeFromGiteeFileList(info, token);
        return fallback != null ? fallback : api;
    }

    /**
     * 网页端 file_list（Gitee 站内「按文件名搜索」用的接口），返回该分支下**全部文件的平铺路径**，
     * 不消耗 {@code /api/v5} 配额，因此是限流时唯一的活路。
     * <p>
     * 代价：拿不到目录项和文件大小 —— 中间目录由路径自动补齐，空目录不会出现。
     *
     * @return 成功返回文件树；仓库不可见 / 接口不可用返回 null（由调用方展示 API 的原始错误）
     */
    private Map<String, Object> buildTreeFromGiteeFileList(GitInfo info, String token) {
        try {
            String branch = resolveBranch(GITEE_API_BASE, info, token);
            String url = "https://gitee.com/" + info.owner + "/" + info.repo
                    + "/file_list?ref=" + URLEncoder.encode(branch, StandardCharsets.UTF_8) + "&path=/";

            HttpResponse<String> resp = sendGet(url, token);
            if (resp == null || resp.statusCode() != 200) return null;

            JsonNode arr = om.readTree(resp.body());
            // 私有仓库未授权时这里会返回登录页 HTML，不是数组 —— 直接判定为未命中
            if (!arr.isArray() || arr.isEmpty()) return null;

            TreeNode rootNode = new TreeNode(info.repo, "directory", "");
            for (JsonNode item : arr) {
                String path = item.asText();
                if (path == null || path.isBlank()) continue;

                String[] parts = path.split("/");
                if (shouldSkip(parts)) continue;

                String type = isBinaryByName(parts[parts.length - 1]) ? "binary" : "file";
                rootNode.add(parts, 0, type, path, 0);
            }

            if (rootNode.children.isEmpty()) return null;
            rootNode.sort();
            return successTree(info.repo, rootNode.childrenAsList());
        } catch (Exception e) {
            log.debug("Gitee file_list 兜底未命中 {}/{}: {}", info.owner, info.repo, e.getMessage());
            return null;
        }
    }

    // ==================== 文件内容获取（GitHub / Gitee 内容 API） ====================

    /**
     * 通过内容 API 取单个文件（GitHub 与 Gitee 的 contents 接口同构，返回 base64）。
     * <p>
     * 不走 raw.githubusercontent.com：部分网络环境下该域名的 TLS 握手会被直接重置，
     * 表现为「Remote host terminated the handshake」，而 api.github.com 一切正常。
     * 公开仓库读取无需任何凭证；私有仓库带上 PAT（token 非空）即可读取。
     */
    private Map<String, Object> fetchFileViaApi(GitInfo info, String filePath, String apiBase, String token) throws Exception {
        String branch = resolveBranch(apiBase, info, token);
        String url = apiBase + "/repos/" + info.owner + "/" + info.repo
                + "/contents/" + encodePath(filePath) + "?ref=" + branch;

        HttpResponse<String> resp = sendGet(url, token);
        if (resp == null) return error("网络异常，无法访问 " + platformOf(url) + " API（请检查本机网络或代理设置）");

        int code = resp.statusCode();
        if (code != 200) {
            return httpError(url, code, resp.body(), token);
        }

        JsonNode node = om.readTree(resp.body());
        if (node.isArray()) {
            return error("目标是一个目录，无法作为文件预览");
        }
        if (node.has("message")) {
            return error("读取失败: " + node.get("message").asText());
        }

        // contents API 对 >1MB 的文件不返回 content，这里提前用 size 拦掉
        long size = node.has("size") ? node.get("size").asLong() : -1;
        if (size > MAX_FILE_SIZE) {
            return error("文件过大（>" + (MAX_FILE_SIZE / 1024) + "KB），无法预览");
        }

        String encoding = node.has("encoding") ? node.get("encoding").asText() : "";
        String rawContent = node.has("content") ? node.get("content").asText() : "";
        if (!"base64".equalsIgnoreCase(encoding) || rawContent.isEmpty()) {
            return error("文件内容不可用（超过 1MB 或为二进制），无法预览");
        }

        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(rawContent);
        } catch (IllegalArgumentException e) {
            return error("文件内容解码失败");
        }
        if (bytes.length > MAX_FILE_SIZE) {
            return error("文件过大（>" + (MAX_FILE_SIZE / 1024) + "KB），无法预览");
        }

        String name = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", new String(bytes, StandardCharsets.UTF_8));
        result.put("path", filePath);
        result.put("size", bytes.length);
        result.put("name", name);
        result.put("language", guessLanguage(name));
        return result;
    }

    /**
     * Gitee 单文件获取。
     * <p>
     * Gitee 的 {@code /api/v5} 匿名调用按 IP 限流 60 次/小时，额度用完整个 API 全线 403
     * （连读公开仓库都不行）。而网页 raw 端点走 CDN、**不消耗该配额**，所以匿名场景优先走 raw：
     * 这样「展开一次文件树 = 1 次 API 调用」，之后点多少个文件都不再花额度。
     * <p>
     * 配了 PAT 时直接走 API（能读私有仓库，认证额度 5000 次/小时，不会成为瓶颈）。
     */
    private Map<String, Object> fetchGiteeFile(GitInfo info, String filePath, String token) throws Exception {
        if (token != null && !token.isBlank()) {
            return fetchFileViaApi(info, filePath, GITEE_API_BASE, token);
        }
        Map<String, Object> raw = fetchFileViaGiteeRaw(info, filePath, null);
        // raw 未命中（目录 / 私有仓库 / 分支不对）时回落 API，好处是能拿到精确的错误原因
        return raw != null ? raw : fetchFileViaApi(info, filePath, GITEE_API_BASE, null);
    }

    /**
     * Gitee 网页 raw 端点直读文件内容（不吃 /api/v5 配额）。
     *
     * @return 命中返回文件内容；未命中返回 null，由调用方回落到 API
     */
    private Map<String, Object> fetchFileViaGiteeRaw(GitInfo info, String filePath, String token) {
        try {
            String branch = resolveBranch(GITEE_API_BASE, info, token);
            String url = "https://gitee.com/" + info.owner + "/" + info.repo
                    + "/raw/" + branch + "/" + encodePath(filePath);
            if (token != null && !token.isBlank()) {
                url += "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            }

            HttpResponse<byte[]> resp = sendGetBytes(url, null);
            if (resp == null || resp.statusCode() != 200) return null;

            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) return null;
            if (bytes.length > MAX_FILE_SIZE) {
                return error("文件过大（>" + (MAX_FILE_SIZE / 1024) + "KB），无法预览");
            }

            String name = lastSegment(filePath);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", new String(bytes, StandardCharsets.UTF_8));
            result.put("path", filePath);
            result.put("size", bytes.length);
            result.put("name", name);
            result.put("language", guessLanguage(name));
            return result;
        } catch (Exception e) {
            log.debug("Gitee raw 未命中 {}/{}: {}", info.owner, filePath, e.getMessage());
            return null;
        }
    }

    private String lastSegment(String path) {
        return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    }

    /** 逐段编码路径，保留 `/` 分隔符 —— 否则路径里的空格、# 等字符会破坏 URL */
    private String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    // ==================== 临时克隆（非 GitHub/Gitee 仓库） ====================

    private Map<String, Object> buildTreeFromTempClone(Long projectId, GitInfo info) throws Exception {
        Path dir = ensureCloned(projectId, info);

        TreeNode rootNode = new TreeNode(info.repo, "directory", "");
        walkLocal(dir, dir, rootNode, 5);
        rootNode.sort();
        return successTree(info.repo, rootNode.childrenAsList());
    }

    private Map<String, Object> fetchFileFromClone(Long projectId, String filePath) throws Exception {
        GitInfo info = getCachedInfo(projectId);
        if (info == null) {
            return error("请先展开文件树以触发远程获取");
        }
        Path dir = ensureCloned(projectId, info);
        Path target = dir.resolve(filePath).normalize();
        if (!target.startsWith(dir)) return error("非法的文件路径");
        if (!Files.exists(target) || Files.isDirectory(target)) return error("文件不存在");
        if (Files.size(target) > MAX_FILE_SIZE) return error("文件过大");
        if (isBinaryByLocal(target)) return error("二进制文件无法预览");

        String content = Files.readString(target, StandardCharsets.UTF_8);
        String name = target.getFileName().toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("path", filePath);
        result.put("size", Files.size(target));
        result.put("name", name);
        result.put("language", guessLanguage(name));
        return result;
    }

    private Path ensureCloned(Long projectId, GitInfo info) throws Exception {
        TempClone existing = cloneCache.get(projectId);
        if (existing != null && Files.exists(existing.dir)) {
            existing.touch();
            return existing.dir;
        }

        Path tmp = Files.createTempDirectory("code-preview-");
        String url = "https://github.com".equals(info.origin)
                ? "https://github.com/" + info.owner + "/" + info.repo + ".git"
                : info.origin;

        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "--depth", "1", "--single-branch",
                "--branch", info.branch, url, tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            deleteDir(tmp);
            throw new RuntimeException("Git 克隆失败: " + output);
        }

        cloneCache.put(projectId, new TempClone(tmp, info));
        return tmp;
    }

    private GitInfo getCachedInfo(Long projectId) {
        TempClone c = cloneCache.get(projectId);
        return c != null ? c.info : null;
    }

    // ==================== 工具方法 ====================

    /** 解析 Git URL → 平台 + owner + repo + branch */
    private GitInfo parseGitUrl(String url, String configuredBranch) {
        if (url == null) return null;
        url = url.trim();

        // 去掉末尾 .git 和 /
        url = url.replaceAll("\\.git$", "");
        url = url.replaceAll("/$", "");

        // 匹配: https://github.com/owner/repo  /  git@github.com:owner/repo
        Pattern p = Pattern.compile(
                "(?:https?://)?(?:www\\.)?(github\\.com|gitee\\.com)[/:]([^/]+)/([^/]+?)(?:/.*)?$");
        Matcher m = p.matcher(url);
        if (m.find()) {
            String host = m.group(1);
            String platform = host.contains("gitee") ? "gitee" : "github";
            String branch = configuredBranch != null && !configuredBranch.isBlank()
                    ? configuredBranch : "main";
            return new GitInfo(platform, host, m.group(2), m.group(3), branch, url);
        }

        // 通用 Git URL：尝试解析
        Pattern generic = Pattern.compile(
                "(?:https?://|git@)([^/:]+)[/:]([^/]+)/([^/]+?)(?:\\.git)?(?:/.*)?$");
        Matcher gm = generic.matcher(url);
        if (gm.find()) {
            String branch = configuredBranch != null && !configuredBranch.isBlank()
                    ? configuredBranch : "main";
            return new GitInfo("generic", gm.group(1), gm.group(2), gm.group(3), branch, url);
        }

        return null;
    }

    /**
     * 解析分支（带缓存）。
     * 命中缓存直接返回，避免每次「预览 / 打开文件」都多打 1~2 个接口 —— Gitee 匿名额度只有 60 次/小时。
     * 解析失败也会被缓存（值回退为配置分支），否则限流期间每次请求都要白跑两趟。
     */
    private String resolveBranch(String apiBase, GitInfo info, String token) {
        String key = apiBase + "|" + info.owner + "/" + info.repo + "|" + info.branch;
        BranchCache cached = branchCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at < BRANCH_TTL_MS) {
            return cached.branch;
        }
        String resolved = resolveBranchRemote(apiBase, info, token);
        branchCache.put(key, new BranchCache(resolved, System.currentTimeMillis()));
        return resolved;
    }

    /** 尝试确认分支存在，失败则回退到仓库默认分支 */
    private String resolveBranchRemote(String apiBase, GitInfo info, String token) {
        try {
            HttpResponse<String> resp = sendGet(
                    apiBase + "/repos/" + info.owner + "/" + info.repo + "/branches/" + info.branch, token);
            if (resp != null && resp.statusCode() == 200) return info.branch;
        } catch (Exception ignored) {}
        // 回退到默认分支
        try {
            HttpResponse<String> resp = sendGet(apiBase + "/repos/" + info.owner + "/" + info.repo, token);
            if (resp != null && resp.statusCode() == 200) {
                JsonNode repo = om.readTree(resp.body());
                if (repo.has("default_branch") && !repo.get("default_branch").isNull()) {
                    return repo.get("default_branch").asText();
                }
            }
        } catch (Exception ignored) {}
        return info.branch; // 尽力了
    }

    // ==================== 凭证 / HTTP 工具 ====================

    /**
     * 生效的 Git 凭证：请求头优先，其次项目里加密保存的。
     * 与 {@code GitController.resolveToken} 保持一致 —— 用户只需在「Git 集成 → GitHub 凭证」
     * 里保存过一次 PAT，代码预览就能读私有仓库。
     */
    private String resolveToken(Project project, String headerToken) {
        if (headerToken != null && !headerToken.isBlank()) return headerToken.trim();
        String enc = project.getGitToken();
        if (enc == null || enc.isBlank()) return null;
        try {
            return secretService.decrypt(enc);
        } catch (Exception e) {
            log.warn("解密 Git Token 失败 (projectId={}): {}", project.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 构造 Authorization 头。
     * 空凭证返回 null —— 不能发空 Authorization，GitHub 会把「Bearer 」判为无效凭证并直接 401，
     * 那样连公开仓库都读不了了。
     */
    private String authHeader(String url, String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim();
        if (t.startsWith("Bearer ") || t.startsWith("token ") || t.startsWith("Basic ")) return t;
        return (url != null && url.contains("gitee.com")) ? "token " + t : "Bearer " + t;
    }

    /** 带凭证的 GET，返回原始响应（含状态码）；网络异常返回 null */
    private HttpResponse<String> sendGet(String url, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DevOps-Platform")
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            String auth = authHeader(url, token);
            if (auth != null) b.header("Authorization", auth);
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Git API GET 失败 {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** 平台名，用于拼提示文案 */
    private String platformOf(String url) {
        return (url != null && url.contains("gitee.com")) ? "Gitee" : "GitHub";
    }

    /** 带凭证的 GET（原始字节，用于 Gitee raw 这类非 JSON 端点）；网络异常返回 null */
    private HttpResponse<byte[]> sendGetBytes(String url, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "DevOps-Platform")
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            String auth = authHeader(url, token);
            if (auth != null) b.header("Authorization", auth);
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            log.warn("Git raw GET 失败 {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 平台匿名 API 是否已限流。
     * Gitee 额度用尽时会对整个 {@code /api/v5} 返回 403，响应体是纯文本
     * {@code 403 Forbidden (Rate Limit Exceeded)}（不是 JSON），必须单独识别，
     * 否则会被当成「凭证权限不足」，把公开仓库的用户误导去配 PAT。
     */
    private boolean isRateLimited(int code, String body) {
        if (code != 403 && code != 429) return false;
        if (body == null || body.isBlank()) return false;
        String b = body.toLowerCase();
        return b.contains("rate limit") || b.contains("too many requests") || b.contains("限流");
    }

    /**
     * 把 HTTP 状态码翻译成用户能看懂、且能据此行动的一句话（+ 可选的行动建议 hint）。
     * 关键点：GitHub 对「私有仓库但没带凭证」和「仓库真的不存在」都返回 404，
     * 所以有无凭证要用不同的措辞，否则用户根本不知道是去哪儿配 PAT。
     */
    private Map<String, Object> httpError(String url, int code, String body, String token) {
        boolean hasToken = token != null && !token.isBlank();
        String plat = platformOf(url);
        String scope = "Gitee".equals(plat) ? "projects" : "repo";

        if (isRateLimited(code, body)) {
            return errorWithHint(
                    plat + " 接口请求已达上限（HTTP " + code + " Rate Limit）：未认证调用按 IP 限流 "
                            + QUOTA_ANON + " 次/小时，当前额度已用完，公开仓库同样受影响",
                    "等约 1 小时后自动恢复；或在「Git 集成 → Git 凭证」保存 " + plat + " 私人令牌"
                            + "（" + scope + " 权限），认证后额度提升到 " + QUOTA_AUTH + " 次/小时。");
        }

        return switch (code) {
            case 401 -> errorWithHint(plat + " 凭证无效或已过期（HTTP 401）",
                    "请在「Git 集成 → Git 凭证」中重新保存 Personal Access Token（" + scope + " 权限）");
            case 403 -> errorWithHint(plat + " 拒绝访问（HTTP 403）：凭证权限不足",
                    "私有仓库需要 " + scope + " 权限的 PAT，请在「Git 集成 → Git 凭证」中确认后重试");
            case 404 -> hasToken
                    ? errorWithHint("仓库或文件不存在，或当前凭证无权访问",
                            "私有仓库需 PAT 具备 " + scope + " 权限")
                    : errorWithHint("仓库不存在，或为私有仓库",
                            "私有仓库请先在「Git 集成 → Git 凭证」中配置 Personal Access Token（" + scope + " 权限）");
            case 409 -> error("仓库是空的（还没有任何提交）");
            case 429 -> errorWithHint("请求过于频繁（HTTP 429）", "请稍后重试");
            default -> error("读取失败 (HTTP " + code + ")");
        };
    }

    private boolean shouldSkip(String[] pathParts) {
        for (String part : pathParts) {
            if (SKIP_DIRS.contains(part)) return true;
        }
        return false;
    }

    private boolean isBinaryByName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private boolean isBinaryByLocal(Path p) {
        if (isBinaryByName(p.getFileName().toString())) return true;
        try {
            byte[] head = Files.readAllBytes(p);
            int len = Math.min(head.length, 256);
            for (int i = 0; i < len; i++) {
                if (head[i] == 0) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    private void walkLocal(Path root, Path dir, TreeNode parent, int depth) throws IOException {
        if (depth <= 0) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (SKIP_DIRS.contains(name)) continue;
                if (name.startsWith(".")) continue;

                String rel = root.relativize(p).toString().replace('\\', '/');
                if (Files.isDirectory(p)) {
                    TreeNode child = new TreeNode(name, "directory", rel);
                    walkLocal(root, p, child, depth - 1);
                    parent.children.put(name, child);
                } else {
                    String type = isBinaryByLocal(p) ? "binary" : "file";
                    long size;
                    try { size = Files.size(p); } catch (IOException e) { size = 0; }
                    parent.children.put(name, new TreeNode(name, type, rel, size));
                }
            }
        }
    }

    private void deleteDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var s = Files.walk(dir)) {
                    s.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException ignored) {}
    }

    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void cleanCloneCache() {
        long now = System.currentTimeMillis();
        cloneCache.entrySet().removeIf(e -> {
            if (now - e.getValue().createdAt > CLONE_TTL_MS) {
                deleteDir(e.getValue().dir);
                return true;
            }
            return false;
        });
        branchCache.entrySet().removeIf(e -> now - e.getValue().at > BRANCH_TTL_MS);
    }

    // ==================== 响应构建 ====================

    private Map<String, Object> successTree(String repoName, List<Map<String, Object>> children) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", repoName);
        root.put("type", "directory");
        root.put("path", "");
        root.put("children", children);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tree", root);
        return result;
    }

    private Map<String, Object> error(String msg) {
        return errorWithHint(msg, null);
    }

    /** @param hint 可选的「下一步该怎么做」，前端会单独一行展示（不参与报错文案匹配） */
    private Map<String, Object> errorWithHint(String msg, String hint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", msg);
        if (hint != null && !hint.isBlank()) result.put("hint", hint);
        return result;
    }

    private String guessLanguage(String fileName) {
        if (fileName.endsWith(".java")) return "java";
        if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) return "javascript";
        if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")) return "typescript";
        if (fileName.endsWith(".py")) return "python";
        if (fileName.endsWith(".go")) return "go";
        if (fileName.endsWith(".rs")) return "rust";
        if (fileName.endsWith(".xml") || fileName.equals("pom.xml")) return "xml";
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) return "html";
        if (fileName.endsWith(".css")) return "css";
        if (fileName.endsWith(".json")) return "json";
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) return "yaml";
        if (fileName.endsWith(".properties")) return "properties";
        if (fileName.endsWith(".md")) return "markdown";
        if (fileName.endsWith(".sql")) return "sql";
        if (fileName.endsWith(".sh") || fileName.equals("mvnw")) return "bash";
        if (fileName.equals("Dockerfile") || fileName.endsWith(".dockerfile")) return "dockerfile";
        if (fileName.equals("Makefile")) return "makefile";
        if (fileName.endsWith(".gradle")) return "groovy";
        if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) return "kotlin";
        if (fileName.endsWith(".c") || fileName.endsWith(".h")) return "c";
        if (fileName.endsWith(".cpp") || fileName.endsWith(".hpp") || fileName.endsWith(".cc")) return "cpp";
        return "plaintext";
    }

    // ==================== 内部类 ====================

    /** Git URL 解析结果 */
    private record GitInfo(String platform, String host, String owner, String repo, String branch, String origin) {}

    /** 分支解析缓存条目 */
    private static class BranchCache {
        final String branch;
        final long at;

        BranchCache(String branch, long at) { this.branch = branch; this.at = at; }
    }

    /** 临时克隆缓存条目 */
    private static class TempClone {
        final Path dir;
        final GitInfo info;
        long createdAt;

        TempClone(Path dir, GitInfo info) {
            this.dir = dir;
            this.info = info;
            this.createdAt = System.currentTimeMillis();
        }
        void touch() { this.createdAt = System.currentTimeMillis(); }
    }

    /** 文件树节点（构建嵌套结构用） */
    private static class TreeNode {
        String name, type, path;
        long size;
        Map<String, TreeNode> children = new LinkedHashMap<>();

        TreeNode(String name, String type, String path) { this(name, type, path, 0); }
        TreeNode(String name, String type, String path, long size) {
            this.name = name; this.type = type; this.path = path; this.size = size;
        }

        /**
         * 把一条 `a/b/c.java` 形式的路径挂进树。
         * <p>
         * ⚠️ 中间目录的 path 必须由**累积路径**算出（`a`、`a/b`），不能留空：
         * 前端 `expandedDirs` 是以 `node.path` 为 key 的，path 为空会让所有目录都指向根节点
         * ——点击任意文件夹变成了展开/收起根，表现为「层次不对、展开对不上」；
         * 只返回文件路径的数据源（Gitee file_list）尤其明显。
         */
        void add(String[] parts, int idx, String type, String fullPath, long size) {
            if (idx >= parts.length) return;
            String key = parts[idx];
            boolean leaf = idx == parts.length - 1;
            String nodePath = leaf
                    ? fullPath
                    : String.join("/", Arrays.copyOfRange(parts, 0, idx + 1));

            TreeNode child = children.computeIfAbsent(key,
                    k -> new TreeNode(k, leaf ? type : "directory", nodePath));

            if (leaf) {
                child.type = type;
                child.path = fullPath;
                child.size = size;
            } else {
                // 目录项可能由文件路径隐式创建（path 已按累积路径算好），
                // 也可能由数据源显式给出（此时以显式值为准）
                if (child.path == null || child.path.isBlank()) child.path = nodePath;
                child.add(parts, idx + 1, type, fullPath, size);
            }
        }

        void sort() {
            List<Map.Entry<String, TreeNode>> entries = new ArrayList<>(children.entrySet());
            entries.sort((a, b) -> {
                boolean aDir = "directory".equals(a.getValue().type);
                boolean bDir = "directory".equals(b.getValue().type);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getKey().compareToIgnoreCase(b.getKey());
            });
            Map<String, TreeNode> sorted = new LinkedHashMap<>();
            for (var e : entries) sorted.put(e.getKey(), e.getValue());
            children = sorted;
            for (TreeNode child : children.values()) child.sort();
        }

        List<Map<String, Object>> childrenAsList() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (TreeNode child : children.values()) {
                list.add(child.toMap());
            }
            return list;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", type);
            map.put("path", path);
            if (size > 0) map.put("size", size);
            if (!children.isEmpty()) {
                map.put("children", childrenAsList());
            }
            return map;
        }
    }
}
