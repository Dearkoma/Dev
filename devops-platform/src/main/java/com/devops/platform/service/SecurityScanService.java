package com.devops.platform.service;

import com.devops.platform.entity.SecurityReport;
import com.devops.platform.entity.SecurityVulnerability;
import com.devops.platform.repository.SecurityReportRepository;
import com.devops.platform.repository.SecurityVulnerabilityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 安全扫描服务 - 封装 Trivy CLI 调用
 * <p>
 * 三种扫描模式:
 *  1) FS       - trivy fs <path>            扫描文件系统(代码/config)
 *  2) DEPENDENCY - trivy fs --scanners vuln  扫描依赖(jar/npm)
 *  3) IMAGE    - trivy image <img:tag>      扫描容器镜像
 * <p>
 * 失败降级:Trivy 未安装 → status=ERRORED,summary="Trivy 未安装"
 * (运维友好,不会因为漏装工具导致流水线直接炸)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityScanService {

    public static final String SCAN_TYPE_FS = "FS";
    public static final String SCAN_TYPE_DEPENDENCY = "DEPENDENCY";
    public static final String SCAN_TYPE_IMAGE = "IMAGE";

    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ERRORED = "ERRORED";

    private final SecurityReportRepository reportRepository;
    private final SecurityVulnerabilityRepository vulnerabilityRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${devops.security.trivy-path:trivy}")
    private String trivyPath;

    @Value("${devops.security.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${devops.security.db-cleanup-on-rescan:true}")
    private boolean cleanupOnRescan;

    /** 是否跳过漏洞库在线更新 (默认 true: 直接用本地缓存库, 扫描快且不依赖外网; 用「手动更新漏洞库」刷新) */
    @Value("${devops.security.skip-db-update:true}")
    private boolean skipDbUpdate;

    /** 漏洞库源 (可替换为国内镜像或私有源) */
    @Value("${devops.security.db-repository:ghcr.io/aquasecurity/trivy-db}")
    private String dbRepository;

    /** Java 依赖漏洞库源 */
    @Value("${devops.security.java-db-repository:ghcr.io/aquasecurity/trivy-java-db}")
    private String javaDbRepository;

    /** 手动更新漏洞库的超时时间 (下载量大, 默认 15 分钟) */
    @Value("${devops.security.db-update-timeout-seconds:900}")
    private int dbUpdateTimeoutSeconds;

    /**
     * 解析 trivy 实际可执行路径
     *
     * 跨平台自适应:
     *  - Windows 后端: 找 tools/trivy/trivy.exe 或用户配的 trivy.exe
     *  - Linux/Mac 后端: 找 tools/trivy/trivy 或用户配的 trivy
     *  - PATH 中的 trivy 始终作为兜底
     *
     * 配置 devops.security.trivy-path 支持:
     *  - "trivy"           → 直接走 PATH
     *  - "/abs/path/trivy" → 绝对路径直接用 (自动探测 .exe 后缀)
     *  - "./tools/trivy"   → 工作目录或用户主目录下查找, 自动补 .exe (Windows)
     *  - "tools/trivy"     → 同上
     *
     * 搜索策略 (按优先级):
     *  1. 配置的路径作为目录 → 找目录内的 trivy / trivy.exe
     *  2. 父目录回退 (IDE 工作目录是子模块时，往上一级找)
     *  3. 配置的路径直接作为可执行文件
     *  4. user.home 下查找
     *  注意: Windows 上不用 canExecute()，只检查 exists()
     *        (Java 在 Windows 上通过 DACL 检查执行权限，经常误判)
     */
    private String resolveTrivyPath() {
        if (trivyPath == null || trivyPath.isBlank() || "trivy".equals(trivyPath)) {
            return "trivy";
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String configuredPath = trivyPath;
        String userDir = System.getProperty("user.dir");
        String userHome = System.getProperty("user.home");

        // 候选目录列表: 工作目录 + 父目录 (IDE 兼容)
        // 当 IDE 把工作目录设在子模块 (如 devops-platform/) 时,
        // ./tools/trivy 找不到, 但 ../tools/trivy 能找到
        java.util.List<File> baseDirs = new java.util.ArrayList<>();
        baseDirs.add(new File(userDir));
        // 父目录: 仅当配置是相对路径且父目录存在时添加
        File parentOfUserDir = new File(userDir).getParentFile();
        if (parentOfUserDir != null) {
            baseDirs.add(parentOfUserDir);
        }

        // —— 策略1: 配置路径作为目录, 找里面的 trivy / trivy.exe ——
        for (File base : baseDirs) {
            File asDir = configuredPath.startsWith("./") || configuredPath.startsWith(".\\")
                    ? new File(base, configuredPath.substring(2))
                    : new File(base, configuredPath);
            // 也尝试原样 (应对 configuredPath 本身就是从某个 base 开始的相对路径)
            if (!asDir.isDirectory() && base != new File(userDir)) {
                asDir = new File(configuredPath);  // fallback to raw
            }
            if (asDir.isDirectory()) {
                String exeName = isWindows ? "trivy.exe" : "trivy";
                File candidate = new File(asDir, exeName);
                if (candidate.exists()) {
                    log.info("Trivy 路径已解析 (目录策略): {}", candidate.getAbsolutePath());
                    return candidate.getAbsolutePath();
                }
            }
        }

        // —— 策略2: 直接作为可执行文件路径 (含 .exe 自动补全) ——
        // Windows: 尝试自动补 .exe
        if (isWindows && !configuredPath.toLowerCase().endsWith(".exe")
                && !configuredPath.toLowerCase().endsWith(".bat")
                && !configuredPath.toLowerCase().endsWith(".cmd")) {
            String searchPath = configuredPath + ".exe";
            for (File base : baseDirs) {
                File candidate = searchPath.startsWith("./") || searchPath.startsWith(".\\")
                        ? new File(base, searchPath.substring(2))
                        : new File(searchPath);
                if (candidate.isAbsolute() && candidate.exists()) {
                    log.info("Trivy 路径已解析 (绝对+.exe): {}", candidate.getAbsolutePath());
                    return candidate.getAbsolutePath();
                }
                if (!candidate.isAbsolute() && candidate.exists()) {
                    log.info("Trivy 路径已解析 (相对+.exe): {}", candidate.getAbsoluteFile().getAbsolutePath());
                    return candidate.getAbsoluteFile().getAbsolutePath();
                }
            }
            // user.home 兜底
            File homeBased = new File(userHome, configuredPath + ".exe");
            if (homeBased.exists()) {
                log.info("Trivy 路径已解析 (home+.exe): {}", homeBased.getAbsolutePath());
                return homeBased.getAbsolutePath();
            }
        }

        // 直接匹配 (无 .exe 后缀情况, 如 Linux)
        File f = new File(configuredPath);
        if (f.isAbsolute() && f.exists()) {
            return f.getAbsolutePath();
        }
        if (f.exists()) {
            return f.getAbsoluteFile().getAbsolutePath();
        }
        for (File base : baseDirs) {
            File candidate = new File(base, configuredPath);
            if (candidate.exists()) {
                log.info("Trivy 路径已解析 (base+配置): {}", candidate.getAbsolutePath());
                return candidate.getAbsolutePath();
            }
        }
        File homeBasedDirect = new File(userHome, configuredPath);
        if (homeBasedDirect.exists()) {
            log.info("Trivy 路径已解析 (home): {}", homeBasedDirect.getAbsolutePath());
            return homeBasedDirect.getAbsolutePath();
        }

        // 都不存在: 返回原值 (Linux 上若无后缀, 原样即可)
        log.warn("Trivy 路径未能解析到实际文件, 将使用原始配置: {} (user.dir={})",
                configuredPath, userDir);
        return configuredPath;
    }

    /**
     * 检查 Trivy 是否可用
     */
    public boolean isTrivyAvailable() {
        String resolvedPath = resolveTrivyPath();
        log.info("检测 Trivy 可用性: resolvedPath={}, user.dir={}",
                resolvedPath, System.getProperty("user.dir"));
        try {
            Process p = new ProcessBuilder(resolvedPath, "--version")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS);
            if (ok && p.exitValue() == 0) {
                log.info("Trivy 可用: {}", resolvedPath);
                return true;
            }
            log.warn("Trivy --version 失败: exitCode={}, ok={}, path={}",
                    ok ? p.exitValue() : "timeout", ok, resolvedPath);
            return false;
        } catch (Exception e) {
            log.warn("Trivy 不可用 ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * FS 扫描 - 扫描代码/config 文件
     * 注意: 不加 @Transactional, Trivy 扫描可能耗时数分钟,
     * 长事务会锁数据库资源; 持久化由 executeScan 内部逐个 save 完成
     */
    public SecurityReport scanFilesystem(Long buildId, Long projectId, Path target,
                                          int thresholdHigh, int thresholdCritical) {
        return executeScan(SCAN_TYPE_FS, buildId, projectId,
                target.toString(), target,
                concat(resolveTrivyPath(), "fs",
                        dbArgs(),
                        "--checks-bundle-repository", "ghcr.io/aquasecurity/trivy-checks",
                        "--format", "json",
                        "--scanners", "vuln,secret,misconfig",
                        "--quiet",
                        target.toString()),
                thresholdHigh, thresholdCritical);
    }

    /**
     * 依赖扫描 - 扫 pom.xml/package.json 等
     */
    public SecurityReport scanDependency(Long buildId, Long projectId, Path target,
                                          int thresholdHigh, int thresholdCritical) {
        return executeScan(SCAN_TYPE_DEPENDENCY, buildId, projectId,
                target.toString(), target,
                concat(resolveTrivyPath(), "fs",
                        dbArgs(),
                        "--format", "json",
                        "--scanners", "vuln",
                        "--quiet",
                        target.toString()),
                thresholdHigh, thresholdCritical);
    }

    /**
     * 镜像扫描
     */
    public SecurityReport scanImage(Long buildId, Long projectId, String imageName,
                                     int thresholdHigh, int thresholdCritical) {
        return executeScan(SCAN_TYPE_IMAGE, buildId, projectId,
                imageName, null,
                concat(resolveTrivyPath(), "image",
                        dbArgs(),
                        "--format", "json",
                        "--quiet",
                        imageName),
                thresholdHigh, thresholdCritical);
    }

    /** 漏洞库相关公共参数: 库源(可配置) + 是否跳过在线更新 */
    private String[] dbArgs() {
        if (skipDbUpdate) {
            // 主库与 Java 库都跳过在线更新: 扫描只读本地缓存, 秒级完成
            // (Java 库约 1.4GB / 有效期 3 天, 若允许扫描时自动更新会把请求卡到超时)
            // 需要刷新时走前端「更新漏洞库」按钮
            return new String[]{
                    "--db-repository", dbRepository,
                    "--java-db-repository", javaDbRepository,
                    "--skip-db-update",
                    "--skip-java-db-update"};
        }
        return new String[]{
                "--db-repository", dbRepository,
                "--java-db-repository", javaDbRepository};
    }

    /** 拼接 trivy 命令: 支持混排 String 与 String[] (数组会被展开) */
    private static String[] concat(Object... parts) {
        java.util.List<String> list = new ArrayList<>(parts.length + 4);
        for (Object p : parts) {
            if (p instanceof String[] arr) {
                java.util.Collections.addAll(list, arr);
            } else {
                list.add((String) p);
            }
        }
        return list.toArray(new String[0]);
    }

    /**
     * 手动更新漏洞库 (DB, 可选 JavaDB)
     * 无论 skipDbUpdate 是否开启都强制在线更新, 供前端「更新漏洞库」按钮调用
     */
    public Map<String, Object> updateDatabase(boolean includeJavaDb) {
        long start = System.currentTimeMillis();
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                resolveTrivyPath(), "image",
                "--download-db-only",
                "--db-repository", dbRepository));
        if (includeJavaDb) {
            cmd.add("--download-java-db-only");
            cmd.add("--java-db-repository");
            cmd.add(javaDbRepository);
        }
        log.info("手动更新漏洞库: {}", String.join(" ", cmd));

        Map<String, Object> result = new java.util.HashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) output.append(line).append('\n');
            }
            boolean finished = p.waitFor(dbUpdateTimeoutSeconds, TimeUnit.SECONDS);
            long cost = System.currentTimeMillis() - start;
            if (!finished) {
                p.destroyForcibly();
                result.put("success", false);
                result.put("message", "漏洞库更新超时 (" + dbUpdateTimeoutSeconds + "s)");
            } else if (p.exitValue() == 0) {
                result.put("success", true);
                result.put("message", "漏洞库更新成功");
            } else {
                result.put("success", false);
                result.put("message", "漏洞库更新失败 (exit=" + p.exitValue() + ")");
            }
            result.put("costSeconds", cost / 1000);
            result.put("outputTail", tailLines(output.toString(), 10));
        } catch (Exception e) {
            log.error("手动更新漏洞库失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("message", "更新失败: " + e.getMessage());
        }
        log.info("漏洞库更新结果: success={}, cost={}s",
                result.get("success"), result.get("costSeconds"));
        return result;
    }

    /**
     * 查询本地漏洞库状态 (解析 trivy --version 输出)
     */
    public Map<String, Object> getDatabaseStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(resolveTrivyPath(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) output.append(line).append('\n');
            }
            p.waitFor(10, TimeUnit.SECONDS);

            // trivy --version 是分段输出, Version / UpdatedAt / DownloadedAt 会在多个段里重复出现:
            //   Version: 0.72.0
            //   Vulnerability DB:  Version / UpdatedAt / NextUpdate / DownloadedAt
            //   Java DB:           Version / UpdatedAt / NextUpdate / DownloadedAt
            //   Check Bundle:      Digest / DownloadedAt
            // 必须按 "当前段" 取值, 否则 Java DB / Check Bundle 的同名 key 会覆盖掉漏洞库的值
            String section = "";
            for (String raw : output.toString().split("\n")) {
                String t = raw.trim();
                if (t.isEmpty()) continue;
                if (t.endsWith(":")) {           // 段头: "Vulnerability DB:" / "Java DB:" / "Check Bundle:"
                    section = t.substring(0, t.length() - 1);
                    continue;
                }
                int idx = t.indexOf(':');
                if (idx < 0) continue;
                String key = t.substring(0, idx).trim();
                String val = t.substring(idx + 1).trim();
                if (val.isEmpty()) continue;

                switch (section) {
                    case "Vulnerability DB" -> {
                        switch (key) {
                            case "Version" -> status.put("dbVersion", val);
                            case "UpdatedAt" -> status.put("dbUpdatedAt", val);
                            case "NextUpdate" -> status.put("dbNextUpdate", val);
                            case "DownloadedAt" -> status.put("dbDownloadedAt", val);
                            default -> { }
                        }
                    }
                    case "Java DB" -> {
                        switch (key) {
                            case "Version" -> status.put("javaDbVersion", val);
                            case "NextUpdate" -> status.put("javaDbNextUpdate", val);
                            case "DownloadedAt" -> status.put("javaDbDownloadedAt", val);
                            default -> { }
                        }
                    }
                    default -> {
                        // 顶层(不在任何段内)的 Version 才是 trivy 自身版本
                        if (section.isEmpty() && "Version".equals(key)) {
                            status.put("trivyVersion", val);
                        }
                    }
                }
            }
        } catch (Exception e) {
            status.put("error", "无法获取漏洞库状态: " + e.getMessage());
        }
        return status;
    }

    /**
     * 核心执行逻辑 - 调 CLI + 解析 JSON + 落库
     */
    private SecurityReport executeScan(String scanType, Long buildId, Long projectId,
                                        String targetDisplay, Path workDir, String[] cmd,
                                        int thresholdHigh, int thresholdCritical) {
        long start = System.currentTimeMillis();
        SecurityReport report = new SecurityReport();
        report.setBuildId(buildId);
        report.setProjectId(projectId);
        report.setScanType(scanType);
        report.setTarget(targetDisplay);
        report.setThresholdHigh(thresholdHigh);
        report.setThresholdCritical(thresholdCritical);
        report.setScannedAt(LocalDateTime.now());

        // 打印完整命令，方便排查
        log.info("执行 Trivy 扫描: type={}, cmd={}, workDir={}",
                scanType, String.join(" ", cmd), workDir);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null) pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) output.append(line).append('\n');
            }
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                report.setStatus(STATUS_ERRORED);
                String tail = tailLines(output.toString(), 5);
                report.setSummary(String.format(
                        "扫描超时 (%ds)%s",
                        timeoutSeconds,
                        tail.isEmpty() ? "" : " | 最后输出: " + tail));
                report.setRawOutput(output.toString());
                report.setCostMs(System.currentTimeMillis() - start);
                log.warn("Trivy 扫描超时: type={}, cmd={}", scanType, String.join(" ", cmd));
                return reportRepository.save(report);
            }

            int exitCode = p.exitValue();
            String rawJson = output.toString();
            // 保护: 单次扫描输出截断到 1MB (MEDIUMTEXT 安全边界)
            if (rawJson.length() > 1_048_576) {
                log.warn("Trivy 输出过大 ({} bytes), 已截断至 1MB", rawJson.length());
                rawJson = rawJson.substring(0, 1_048_576) + "\n... [输出过长已截断]";
            }
            report.setRawOutput(rawJson);
            report.setCostMs(System.currentTimeMillis() - start);

            // Trivy 退出码: 0=无漏洞, 1=有漏洞, 2=错误
            if (exitCode == 2) {
                report.setStatus(STATUS_ERRORED);
                String tail = tailLines(rawJson, 10);
                report.setSummary(String.format(
                        "Trivy 执行错误 (exit=2)%s",
                        tail.isEmpty() ? "" : " | " + tail));
                log.warn("Trivy 执行错误 (exit=2): type={}, outputTail={}", scanType, tail);
                return reportRepository.save(report);
            }

            // 保护: Trivy 初始化失败时输出的是文本日志而非 JSON（如 DB 下载失败）
            // 日志以时间戳开头 "2026-08-05T22:37:32+08:00\tFATAL..."
            // 合法 JSON 以 { 或 [ 开头, 快速检测避免 parseAndPersist 抛异常
            String trimmed = rawJson.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                report.setStatus(STATUS_ERRORED);
                String tail = tailLines(rawJson, 5);
                report.setSummary("Trivy 异常退出 (exit=" + exitCode + "): " + tail);
                log.warn("Trivy 输出非 JSON (exit={}): type={}, outputTail={}", exitCode, scanType, tail);
                return reportRepository.save(report);
            }

            // exit 0/1: 扫描成功, 先标记状态再保存 (parseAndPersist 会更新)
            report.setStatus(STATUS_PASSED);
            report.setSummary("扫描中...");
            // 先保存 report 以获取 ID（漏洞明细的 reportId 外键需要）
            report = reportRepository.save(report);

            // 解析 JSON 并保存漏洞明细
            parseAndPersist(rawJson, report, thresholdHigh, thresholdCritical);

            // 更新统计字段
            return reportRepository.save(report);

        } catch (Exception e) {
            log.error("安全扫描失败: type={}, cmd={}, error={}",
                    scanType, String.join(" ", cmd), e.getMessage(), e);
            report.setStatus(STATUS_ERRORED);
            report.setSummary("执行失败: " + e.getMessage());
            report.setCostMs(System.currentTimeMillis() - start);
            return reportRepository.save(report);
        }
    }

    /** 取最后 N 行 (去空行), 用于错误摘要 */
    private static String tailLines(String output, int n) {
        if (output == null || output.isBlank()) return "";
        String[] lines = output.split("\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = lines.length - 1; i >= 0 && count < n; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.insert(0, " → ");
            // 截断过长的单行
            if (trimmed.length() > 200) trimmed = trimmed.substring(0, 200) + "...";
            sb.insert(0, trimmed);
            count++;
        }
        return sb.toString();
    }

    /**
     * 解析 Trivy JSON 输出 → 计数 + 漏洞明细
     */
    private void parseAndPersist(String json, SecurityReport report,
                                  int thresholdHigh, int thresholdCritical) {
        int critical = 0, high = 0, medium = 0, low = 0, unknown = 0, total = 0;
        List<SecurityVulnerability> vulns = new ArrayList<>();
        StringBuilder summary = new StringBuilder();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("Results");
            if (!results.isArray()) results = objectMapper.createArrayNode();

            for (JsonNode result : results) {
                String target = result.path("Target").asText("");
                String className = result.path("Class").asText("");  // os-pkgs / lang-pkgs / secret / config

                JsonNode vulnerabilities = result.path("Vulnerabilities");
                if (vulnerabilities.isArray()) {
                    for (JsonNode v : vulnerabilities) {
                        total++;
                        String severity = v.path("Severity").asText("UNKNOWN");
                        switch (severity) {
                            case "CRITICAL" -> critical++;
                            case "HIGH" -> high++;
                            case "MEDIUM" -> medium++;
                            case "LOW" -> low++;
                            default -> unknown++;
                        }

                        SecurityVulnerability vuln = new SecurityVulnerability();
                        vuln.setReportId(report.getId());
                        vuln.setBuildId(report.getBuildId());
                        vuln.setProjectId(report.getProjectId());
                        vuln.setCveId(v.path("VulnerabilityID").asText(null));
                        vuln.setTitle(firstNonEmpty(
                                v.path("Title").asText(null),
                                v.path("Description").asText(null)));
                        vuln.setSeverity(severity);
                        JsonNode cvss = v.path("CVSS");
                        if (cvss.has("nvd")) {
                            JsonNode score = cvss.path("nvd").path("V3Score");
                            if (!score.isMissingNode() && !score.isNull()) {
                                vuln.setCvssScore(score.asDouble());
                            }
                        }
                        vuln.setPackageName(v.path("PkgName").asText(null));
                        vuln.setInstalledVersion(v.path("InstalledVersion").asText(null));
                        vuln.setFixedVersion(v.path("FixedVersion").asText(null));
                        vuln.setSource("lang-pkgs".equals(className) ? "dependency" : className);
                        vuln.setFilePath(target);
                        vuln.setDescription(v.path("Description").asText(null));
                        JsonNode refs = v.path("References");
                        if (refs.isArray() && refs.size() > 0) {
                            vuln.setReference(refs.get(0).asText(null));
                        }
                        vulns.add(vuln);
                    }
                }

                JsonNode secrets = result.path("Secrets");
                if (secrets.isArray()) {
                    for (JsonNode s : secrets) {
                        total++;
                        String severity = s.path("Severity").asText("HIGH");
                        // secrets 至少算 HIGH
                        if ("CRITICAL".equals(severity) || "HIGH".equals(severity)) {
                            high++;
                        } else if ("MEDIUM".equals(severity)) {
                            medium++;
                        } else {
                            low++;
                        }
                        SecurityVulnerability vuln = new SecurityVulnerability();
                        vuln.setReportId(report.getId());
                        vuln.setBuildId(report.getBuildId());
                        vuln.setProjectId(report.getProjectId());
                        vuln.setCveId(s.path("RuleID").asText(null));
                        vuln.setTitle("Secret leak: " + s.path("RuleID").asText());
                        vuln.setSeverity(severity);
                        vuln.setSource("secret");
                        vuln.setFilePath(target);
                        vuln.setDescription(s.path("Match").asText(""));
                        vulns.add(vuln);
                    }
                }

                JsonNode misconfigs = result.path("Misconfigurations");
                if (misconfigs.isArray()) {
                    for (JsonNode m : misconfigs) {
                        total++;
                        String severity = m.path("Severity").asText("MEDIUM");
                        switch (severity) {
                            case "CRITICAL" -> critical++;
                            case "HIGH" -> high++;
                            case "MEDIUM" -> medium++;
                            case "LOW" -> low++;
                            default -> unknown++;
                        }
                        SecurityVulnerability vuln = new SecurityVulnerability();
                        vuln.setReportId(report.getId());
                        vuln.setBuildId(report.getBuildId());
                        vuln.setProjectId(report.getProjectId());
                        vuln.setCveId(m.path("ID").asText(null));
                        vuln.setTitle(m.path("Title").asText());
                        vuln.setSeverity(severity);
                        vuln.setSource("misconfig");
                        vuln.setFilePath(target);
                        vuln.setDescription(m.path("Description").asText(""));
                        vuln.setReference(m.path("Resolution").asText(null));
                        vulns.add(vuln);
                    }
                }
            }

            report.setCriticalCount(critical);
            report.setHighCount(high);
            report.setMediumCount(medium);
            report.setLowCount(low);
            report.setUnknownCount(unknown);
            report.setTotalCount(total);
            report.setDetails(objectMapper.writeValueAsString(root));

            // 状态判定
            boolean failed = critical > thresholdCritical || high > thresholdHigh;
            report.setStatus(failed ? STATUS_FAILED : STATUS_PASSED);

            // 摘要
            summary.append(String.format(
                    "%s 扫描完成: 严重 %d / 高 %d / 中 %d / 低 %d / 未知 %d (合计 %d) | 阈值: critical≤%d, high≤%d",
                    report.getScanType(), critical, high, medium, low, unknown, total,
                    thresholdCritical, thresholdHigh));
            if (failed) {
                summary.append(" | 状态: ❌ 超过阈值, 已阻断");
            } else if (total > 0) {
                summary.append(" | 状态: ✅ 通过 (有警告)");
            } else {
                summary.append(" | 状态: ✅ 干净");
            }
            report.setSummary(summary.toString());

            // 落库漏洞明细
            if (!vulns.isEmpty()) {
                vulnerabilityRepository.saveAll(vulns);
            }

        } catch (Exception e) {
            log.error("解析 Trivy JSON 失败: {}", e.getMessage(), e);
            report.setStatus(STATUS_ERRORED);
            report.setSummary("JSON 解析失败: " + e.getMessage());
        }
    }

    private static String firstNonEmpty(String... arr) {
        for (String s : arr) {
            if (s != null && !s.isBlank()) {
                return s.length() > 500 ? s.substring(0, 500) : s;
            }
        }
        return null;
    }
}
