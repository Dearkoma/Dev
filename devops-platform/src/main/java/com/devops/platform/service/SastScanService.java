package com.devops.platform.service;

import com.devops.platform.entity.SastFinding;
import com.devops.platform.entity.SastScanReport;
import com.devops.platform.repository.SastFindingRepository;
import com.devops.platform.repository.SastScanReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SAST 静态应用安全测试服务 — 真实扫描源码
 *
 * 双引擎:
 *  1) Semgrep (优先) — 若系统安装了 semgrep, 调用 `semgrep scan --json --config auto`
 *  2) 内置模式扫描器 (降级) — 零依赖的规则引擎, 扫描常见漏洞模式:
 *     硬编码密钥 / SQL 注入 / XSS / 命令注入 / 不安全反序列化 / 弱加密算法 /
 *     路径穿越 / 硬编码数据库口令 / 危险 API
 *
 * 与 Trivy (SCA) 形成互补: Trivy 扫"第三方依赖漏洞", SAST 扫"自己写的代码漏洞"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SastScanService {

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final SastScanReportRepository reportRepository;
    private final SastFindingRepository findingRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${devops.security.semgrep-path:semgrep}")
    private String semgrepPath;

    @Value("${devops.security.timeout-seconds:300}")
    private int timeoutSeconds;

    // ==================== 内置 SAST 规则 ====================

    private static final class Rule {
        final String id;
        final String title;
        final String severity;
        final String category;
        final String cwe;
        final String message;
        final Pattern pattern;
        final Set<String> extensions;  // null = 所有文本文件

        Rule(String id, String title, String severity, String category, String cwe,
             String message, String regex, String... extensions) {
            this.id = id;
            this.title = title;
            this.severity = severity;
            this.category = category;
            this.cwe = cwe;
            this.message = message;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.extensions = extensions.length == 0 ? null : Set.of(extensions);
        }
    }

    private static final List<Rule> RULES = List.of(
        // 硬编码密钥/口令
        new Rule("secret.hardcoded-password", "硬编码密码", "HIGH", "SECRET", "CWE-798",
            "源代码中疑似硬编码了密码，应改为环境变量或密钥管理服务注入",
            "(?i)(password|passwd|pwd|secret|token)\\s*[=:]\\s*[\"'][^\"'&$<>{}\\s]{6,}[\"']"),
        new Rule("secret.hardcoded-api-key", "硬编码 API 密钥", "CRITICAL", "SECRET", "CWE-798",
            "疑似硬编码了 API 密钥/Access Key，泄露后将导致账号被接管",
            "(?i)(api[_-]?key|access[_-]?key|secret[_-]?key|aws_secret|private[_-]?key)\\s*[=:]\\s*[\"'][A-Za-z0-9_\\-/]{16,}[\"']"),
        new Rule("secret.jwt-secret", "硬编码 JWT 密钥", "HIGH", "SECRET", "CWE-798",
            "JWT 签名密钥硬编码在代码中，攻击者可伪造任意身份令牌",
            "(?i)(jwt[_-]?secret|signing[_-]?key|encryption[_-]?key)\\s*[=:]\\s*[\"'][^\"']{8,}[\"']"),
        new Rule("secret.jdbc-password", "数据库连接串含明文口令", "HIGH", "SECRET", "CWE-798",
            "JDBC 连接串中硬编码了数据库口令，应使用环境变量注入",
            "(?i)jdbc:[a-z]+://[^\"'\\s]*password=[^&;\"'\\s]+"),

        // SQL 注入
        new Rule("injection.sql-concat", "SQL 注入风险(字符串拼接)", "CRITICAL", "INJECTION", "CWE-89",
            "SQL 语句使用字符串拼接用户输入，存在 SQL 注入漏洞，应使用 PreparedStatement",
            "(?i)(select|insert|update|delete|where)\\s+.*[\"']\\s*\\+\\s*[a-zA-Z_]", ".java"),
        new Rule("injection.sql-string-format", "SQL 注入风险(String.format)", "CRITICAL", "INJECTION", "CWE-89",
            "使用 String.format 拼接 SQL 用户输入，存在 SQL 注入漏洞",
            "(?i)String\\.format\\s*\\(\\s*[\"'][^\"']*(select|insert|update|delete)", ".java"),

        // XSS
        new Rule("xss.inner-html", "XSS 风险(innerHTML)", "HIGH", "INJECTION", "CWE-79",
            "将未经净化的数据写入 innerHTML，存在存储型 XSS，应使用 textContent 或净化",
            "\\.innerHTML\\s*=", ".js", ".jsx", ".ts", ".tsx", ".html"),
        new Rule("xss-document-write", "XSS 风险(document.write)", "MEDIUM", "INJECTION", "CWE-79",
            "使用 document.write 输出动态内容，存在 XSS 风险",
            "document\\.write\\s*\\(", ".js", ".html"),

        // 命令注入
        new Rule("injection.command-exec", "命令注入风险", "CRITICAL", "INJECTION", "CWE-78",
            "将用户输入拼接到系统命令中执行，存在命令注入，应使用参数数组或白名单",
            "(?i)(Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder\\s*\\(\\s*[^)]*\\+).*",
            ".java"),
        new Rule("injection.command-concat", "命令注入风险(字符串拼接)", "CRITICAL", "INJECTION", "CWE-78",
            "使用字符串拼接构造 shell 命令，存在命令注入风险",
            "(?i)(exec|system|popen)\\s*\\(\\s*[\"'][^\"']*\\+", ".java", ".py", ".c", ".cpp", ".go"),

        // 不安全反序列化
        new Rule("deserialize.objectinputstream", "不安全反序列化", "CRITICAL", "SECURITY", "CWE-502",
            "直接使用 ObjectInputStream 反序列化不可信数据，可导致 RCE，应使用白名单校验",
            "new\\s+ObjectInputStream", ".java"),
        new Rule("deserialize.pickle", "不安全反序列化(pickle)", "HIGH", "SECURITY", "CWE-502",
            "pickle.load 反序列化不可信数据可导致代码执行，应使用 json 等安全格式",
            "pickle\\.load", ".py"),

        // 弱加密
        new Rule("crypto.md5", "弱哈希算法 MD5", "MEDIUM", "CRYPTO", "CWE-327",
            "使用 MD5 哈希，已被破解，应使用 SHA-256 及以上",
            "(?i)(MessageDigest\\.getInstance\\(\\s*[\"']MD5|hashlib\\.md5|md5\\s*\\()", ".java", ".py"),
        new Rule("crypto.sha1", "弱哈希算法 SHA-1", "MEDIUM", "CRYPTO", "CWE-327",
            "使用 SHA-1 哈希，已被碰撞攻击，应使用 SHA-256 及以上",
            "(?i)(MessageDigest\\.getInstance\\(\\s*[\"']SHA-1|hashlib\\.sha1)", ".java", ".py"),
        new Rule("crypto.des", "弱加密算法 DES", "HIGH", "CRYPTO", "CWE-327",
            "使用 DES 加密，密钥过短已被破解，应使用 AES-256",
            "(?i)(\"DES\"|DES/ECB|DES/CBC|DESede)", ".java"),
        new Rule("crypto.ecb", "不安全的 ECB 加密模式", "MEDIUM", "CRYPTO", "CWE-327",
            "使用 ECB 分组加密模式，无法隐藏数据模式，应使用 CBC/GCM",
            "(?i)AES/ECB", ".java"),

        // 路径穿越
        new Rule("traversal.path", "路径穿越风险", "HIGH", "SECURITY", "CWE-22",
            "文件路径由用户输入直接拼接，存在路径穿越，应规范化并限制在安全目录内",
            "(?i)(new\\s+File\\s*\\([^)]*\\+|Files\\.read[^)]*\\+|open\\s*\\([^)]*\\+)", ".java", ".py", ".go"),

        // 危险 API / 调试后门
        new Rule("danger.print-stacktrace", "生产代码 printStackTrace", "LOW", "CORRECTNESS", "CWE-209",
            "生产环境使用 printStackTrace 可能泄露内部信息，应使用日志框架",
            "\\.printStackTrace\\s*\\(", ".java"),
        new Rule("danger.system-exit", "直接调用 System.exit", "LOW", "CORRECTNESS", "CWE-382",
            "直接调用 System.exit 会终止整个 JVM，服务应优雅关闭",
            "System\\.exit\\s*\\(", ".java"),
        new Rule("danger.eval", "动态代码执行 eval", "CRITICAL", "INJECTION", "CWE-95",
            "使用 eval 执行动态代码，可导致任意代码执行",
            "\\beval\\s*\\(", ".js", ".py")
    );

    /** 扫描跳过的大文件阈值 (byte) */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    // ==================== 入口 ====================

    /**
     * 扫描源码。优先 semgrep，降级到内置扫描器。
     */
    public SastScanReport scanSource(Long buildId, Long projectId, Path target,
                                     int thresholdHigh, int thresholdCritical) {
        long start = System.currentTimeMillis();

        // 检测 semgrep
        if (isSemgrepAvailable()) {
            try {
                return scanWithSemgrep(buildId, projectId, target, thresholdHigh, thresholdCritical, start);
            } catch (Exception e) {
                log.warn("Semgrep 扫描异常, 降级到内置扫描器: {}", e.getMessage());
            }
        } else {
            log.info("Semgrep 不可用, 使用内置模式扫描器 (零依赖降级)");
        }

        return scanWithBuiltinRules(buildId, projectId, target, thresholdHigh, thresholdCritical, start);
    }

    // ==================== Semgrep 引擎 ====================

    private String resolveSemgrepPath() {
        if (semgrepPath == null || semgrepPath.isBlank() || "semgrep".equals(semgrepPath)) {
            return "semgrep";
        }
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File f = new File(semgrepPath);
        if (isWindows && !f.getName().toLowerCase().endsWith(".exe")) {
            File exe = new File(semgrepPath + ".exe");
            if (exe.exists()) return exe.getAbsolutePath();
        }
        if (f.exists()) return f.getAbsolutePath();
        return semgrepPath;
    }

    public boolean isSemgrepAvailable() {
        String path = resolveSemgrepPath();
        try {
            Process p = new ProcessBuilder(path, "--version").redirectErrorStream(true).start();
            boolean ok = p.waitFor(8, TimeUnit.SECONDS);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private SastScanReport scanWithSemgrep(Long buildId, Long projectId, Path target,
                                           int thresholdHigh, int thresholdCritical, long start) throws Exception {
        String[] cmd = {resolveSemgrepPath(), "scan", "--json", "--config", "auto",
                "--no-git-ignore", "--quiet", target.toString()};

        SastScanReport report = newReport(buildId, projectId, "SEMGREP");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(target.toFile());
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
            report.setStatus(STATUS_FAILED);
            report.setSummary("SAST 扫描超时 (" + timeoutSeconds + "s)");
            return reportRepository.save(report);
        }

        String raw = output.toString();
        report.setFindingsJson(raw.length() > 1_048_576 ? raw.substring(0, 1_048_576) : raw);

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            report.setStatus(STATUS_FAILED);
            report.setSummary("Semgrep 输出解析失败: " + e.getMessage());
            return reportRepository.save(report);
        }

        report = reportRepository.save(report);
        parseSemgrepFindings(root, report);
        finalizeReport(report, thresholdHigh, thresholdCritical, start);
        return reportRepository.save(report);
    }

    private void parseSemgrepFindings(JsonNode root, SastScanReport report) {
        JsonNode results = root.path("results");
        if (!results.isArray()) return;
        for (JsonNode r : results) {
            SastFinding f = new SastFinding();
            f.setReportId(report.getId());
            f.setBuildId(report.getBuildId());
            f.setProjectId(report.getProjectId());
            f.setRuleId(r.path("check_id").asText(""));
            f.setTitle(r.path("check_id").asText(""));
            String sev = r.path("extra").path("severity").asText("MEDIUM");
            f.setSeverity(mapSeverity(sev));
            f.setCategory(mapCategory(r.path("check_id").asText("")));
            f.setFilePath(r.path("path").asText(""));
            f.setLineNumber(r.path("start").path("line").asInt(0));
            f.setLanguage(r.path("extra").path("metadata").path("language").asText(""));
            f.setCodeSnippet(r.path("extra").path("lines").asText(""));
            f.setMessage(r.path("extra").path("message").asText(""));
            f.setCweId(extractCwe(r.path("extra").path("metadata").asText("")));
            findingRepository.save(f);
        }
    }

    // ==================== 内置模式扫描器 ====================

    private SastScanReport scanWithBuiltinRules(Long buildId, Long projectId, Path target,
                                                int thresholdHigh, int thresholdCritical, long start) {
        SastScanReport report = newReport(buildId, projectId, "BUILTIN");
        report = reportRepository.save(report);

        List<SastFinding> findings = new ArrayList<>();
        try {
            scanDirectory(target, target, report, findings);
        } catch (Exception e) {
            log.error("内置 SAST 扫描异常: {}", e.getMessage(), e);
            report.setStatus(STATUS_FAILED);
            report.setSummary("扫描异常: " + e.getMessage());
            return reportRepository.save(report);
        }

        if (!findings.isEmpty()) {
            findingRepository.saveAll(findings);
        }
        // 保存 JSON 摘要供前端展示
        try {
            report.setFindingsJson(objectMapper.writeValueAsString(findings.stream()
                    .map(f -> Map.of(
                        "ruleId", f.getRuleId(), "title", f.getTitle(),
                        "severity", f.getSeverity(), "filePath", f.getFilePath(),
                        "line", f.getLineNumber() == null ? 0 : f.getLineNumber(),
                        "message", f.getMessage() == null ? "" : f.getMessage()))
                    .toList()));
        } catch (Exception ignored) {}

        finalizeReport(report, thresholdHigh, thresholdCritical, start);
        return reportRepository.save(report);
    }

    private void scanDirectory(Path root, Path dir, SastScanReport report, List<SastFinding> findings) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                String name = file.getName();
                // 跳过无关目录，避免误报 vendor/依赖
                if (name.equals(".git") || name.equals("node_modules") || name.equals("target")
                        || name.equals("dist") || name.equals("build") || name.equals(".idea")
                        || name.equals(".mvn") || name.equals("vendor") || name.equals(".venv")
                        || name.equals("__pycache__")) {
                    continue;
                }
                if (findings.size() >= 5000) return;  // 安全上限
                scanDirectory(root, file.toPath(), report, findings);
            } else {
                scanFile(root, file.toPath(), report, findings);
            }
        }
    }

    private void scanFile(Path root, Path file, SastScanReport report, List<SastFinding> findings) {
        String name = file.getFileName().toString().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";

        // 只扫描源码/配置文件
        if (!isSourceFile(ext)) return;

        try {
            if (Files.size(file) > MAX_FILE_SIZE) return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // 计算相对路径
            String relPath;
            try {
                relPath = root.relativize(file).toString().replace('\\', '/');
            } catch (Exception e) {
                relPath = file.toString();
            }

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (Rule rule : RULES) {
                    if (rule.extensions != null && !rule.extensions.contains(ext)) continue;
                    Matcher m = rule.pattern.matcher(line);
                    if (m.find()) {
                        // 跳过注释行误报（简单判断）
                        String trimmed = line.trim();
                        if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")
                                || trimmed.startsWith("/*")) continue;

                        SastFinding f = new SastFinding();
                        f.setReportId(report.getId());
                        f.setBuildId(report.getBuildId());
                        f.setProjectId(report.getProjectId());
                        f.setRuleId(rule.id);
                        f.setTitle(rule.title);
                        f.setSeverity(rule.severity);
                        f.setCategory(rule.category);
                        f.setCweId(rule.cwe);
                        f.setMessage(rule.message);
                        f.setFilePath(relPath);
                        f.setLineNumber(i + 1);
                        f.setLanguage(ext);
                        f.setCodeSnippet(trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed);
                        findings.add(f);
                        break;  // 一行只记一条最高优先级规则命中
                    }
                }
            }
        } catch (Exception ignored) {
            // 二进制文件或读取失败，跳过
        }
    }

    private boolean isSourceFile(String ext) {
        return switch (ext) {
            case "java", "js", "jsx", "ts", "tsx", "py", "go", "c", "cpp", "h",
                 "rs", "rb", "php", "sql", "xml", "yml", "yaml", "json", "html",
                 "properties", "sh", "bash", "gradle", "kt", "scala" -> true;
            default -> false;
        };
    }

    // ==================== 公共工具 ====================

    private SastScanReport newReport(Long buildId, Long projectId, String scanner) {
        SastScanReport report = new SastScanReport();
        report.setBuildId(buildId);
        report.setProjectId(projectId);
        report.setScanner(scanner);
        report.setStatus(STATUS_COMPLETED);
        report.setCreatedAt(LocalDateTime.now());
        return report;
    }

    private void finalizeReport(SastScanReport report, int thresholdHigh, int thresholdCritical, long start) {
        List<SastFinding> all = findingRepository.findByReportId(report.getId());
        int critical = 0, high = 0, medium = 0, low = 0;
        for (SastFinding f : all) {
            switch (f.getSeverity()) {
                case "CRITICAL" -> critical++;
                case "HIGH" -> high++;
                case "MEDIUM" -> medium++;
                case "LOW" -> low++;
            }
        }
        report.setTotalFindings(all.size());
        report.setCriticalCount(critical);
        report.setHighCount(high);
        report.setMediumCount(medium);
        report.setLowCount(low);

        boolean failed = critical > thresholdCritical || high > thresholdHigh;
        report.setStatus(failed ? STATUS_FAILED : STATUS_COMPLETED);
        report.setSummary(String.format(
            "SAST(%s) 扫描完成: 严重 %d / 高 %d / 中 %d / 低 %d (合计 %d) | 阈值 critical≤%d, high≤%d | %s",
            report.getScanner(), critical, high, medium, low, all.size(),
            thresholdCritical, thresholdHigh, failed ? "❌ 超阈值阻断" : "✅ 通过"));
    }

    private String mapSeverity(String sev) {
        return switch (sev.toUpperCase()) {
            case "ERROR" -> "CRITICAL";
            case "WARNING" -> "HIGH";
            case "INFO" -> "LOW";
            default -> sev.toUpperCase();
        };
    }

    private String mapCategory(String checkId) {
        String id = checkId.toLowerCase();
        if (id.contains("crypto") || id.contains("hash")) return "CRYPTO";
        if (id.contains("sql") || id.contains("injection") || id.contains("xss") || id.contains("exec")) return "INJECTION";
        if (id.contains("secret") || id.contains("password") || id.contains("key")) return "SECRET";
        if (id.contains("deserialize")) return "SECURITY";
        return "CORRECTNESS";
    }

    private String extractCwe(String metadata) {
        if (metadata == null) return null;
        Matcher m = Pattern.compile("CWE-\\d+").matcher(metadata);
        return m.find() ? m.group() : null;
    }

    // ==================== 查询 ====================

    public List<SastScanReport> listReports(Long projectId) {
        return projectId != null
                ? reportRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                : reportRepository.findAll();
    }

    public List<SastFinding> listFindings(Long reportId) {
        return findingRepository.findByReportId(reportId);
    }

    public Optional<SastScanReport> getReport(Long id) {
        return reportRepository.findById(id);
    }
}
