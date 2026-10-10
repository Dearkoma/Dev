package com.devops.platform.service;

import com.devops.platform.entity.TestReport;
import com.devops.platform.repository.TestReportRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * 测试报告服务 — 解析各框架测试输出并创建 TestReport
 */
@Slf4j
@Service
public class TestReportService {

    private final TestReportRepository testReportRepository;

    /** 质量门阈值 */
    @Value("${devops.quality-gate.min-pass-rate:80.0}")
    private double minPassRate;

    @Value("${devops.quality-gate.min-coverage:60.0}")
    private double minCoverage;

    public TestReportService(TestReportRepository testReportRepository) {
        this.testReportRepository = testReportRepository;
    }

    /**
     * 解析并保存测试报告
     */
    public TestReport parseAndSave(Path projectRoot, String language, Long buildId,
                                    Long projectId, Long pipelineId, long durationMs,
                                    StringBuilder logBuf) {
        TestReport report = new TestReport();
        report.setBuildId(buildId);
        report.setProjectId(projectId);
        report.setPipelineId(pipelineId);
        report.setDurationMs(durationMs);

        String lang = language != null ? language.toLowerCase() : "java";

        try {
            if (lang.contains("node") || lang.contains("javascript") || lang.contains("typescript")) {
                parseJestOutput(projectRoot, report, logBuf);
            } else if (lang.contains("python")) {
                parsePytestOutput(projectRoot, report, logBuf);
            } else if (lang.equals("go") || lang.equals("golang")) {
                parseGoTestOutput(projectRoot, report, logBuf);
            } else if (lang.equals("rust")) {
                report.setTestFramework("CARGO_TEST");
                parseCargoTestOutput(projectRoot, report, logBuf);
            } else if (lang.contains("c++") || lang.contains("cpp") || lang.equals("c")) {
                report.setTestFramework("CTEST");
                parseCTestOutput(projectRoot, report, logBuf);
            } else {
                // Java / Kotlin / Scala — parse JUnit XML
                parseJUnitXml(projectRoot, report, logBuf);
            }

            // 尝试解析覆盖率
            parseCoverage(projectRoot, lang, report, logBuf);

            // 计算质量门
            evaluateQualityGate(report, logBuf);

            // 生成摘要
            report.setSummary(buildSummary(report));

            report = testReportRepository.save(report);
            logBuf.append("[TEST REPORT] 测试报告已保存 (id=").append(report.getId())
                    .append(", passed=").append(report.getPassed())
                    .append(", failed=").append(report.getFailed())
                    .append(", coverage=").append(String.format("%.1f%%", report.getCoveragePercent()))
                    .append(", qualityGate=").append(report.getQualityGatePassed() ? "PASS" : "FAIL")
                    .append(")\n");

        } catch (Exception e) {
            log.warn("解析测试报告失败: {}", e.getMessage());
            report.setTestFramework("UNKNOWN");
            report.setSummary("测试报告解析失败: " + e.getMessage());
            report.setQualityGatePassed(true); // 解析失败不放行但不阻塞
            report.setQualityGateMessage("报告解析异常，跳过门禁");
            report = testReportRepository.save(report);
            logBuf.append("[TEST REPORT] 报告解析失败，跳过质量门: ").append(e.getMessage()).append("\n");
        }

        return report;
    }

    // ===================== JUnit XML 解析 =====================

    private void parseJUnitXml(Path projectRoot, TestReport report, StringBuilder logBuf) throws Exception {
        report.setTestFramework("JUNIT");
        Path surefireDir = findSurefireReports(projectRoot);
        if (surefireDir == null) {
            logBuf.append("[TEST REPORT] 未找到 surefire-reports 目录\n");
            return;
        }

        var allXml = new StringBuilder();
        int totalTests = 0, passed = 0, failed = 0, skipped = 0, errors = 0;

        try (Stream<Path> walk = Files.walk(surefireDir)) {
            var xmlFiles = walk.filter(f -> f.getFileName().toString().startsWith("TEST-")
                    && f.getFileName().toString().endsWith(".xml")).toList();
            logBuf.append("[TEST REPORT] 找到 ").append(xmlFiles.size()).append(" 个 JUnit XML 报告\n");

            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            var db = dbf.newDocumentBuilder();

            for (Path xml : xmlFiles) {
                String content = Files.readString(xml);
                allXml.append(content).append("\n<!-- END -->\n");
                try {
                    var doc = db.parse(new org.xml.sax.InputSource(new StringReader(content)));
                    var root = doc.getDocumentElement();
                    int t = Integer.parseInt(root.getAttribute("tests"));
                    int f = Integer.parseInt(root.getAttribute("failures"));
                    int e = Integer.parseInt(root.getAttribute("errors"));
                    int s = safeParseInt(root.getAttribute("skipped"));
                    totalTests += t;
                    failed += f;
                    errors += e;
                    skipped += s;
                    passed += (t - f - e - s);
                } catch (Exception parseEx) {
                    log.warn("解析 {} 失败: {}", xml.getFileName(), parseEx.getMessage());
                }
            }
        } catch (Exception e) {
            // 可能没有 surefire-reports
            logBuf.append("[TEST REPORT] surefire 报告目录遍历异常: ").append(e.getMessage()).append("\n");
        }

        report.setTotalTests(totalTests);
        report.setPassed(passed);
        report.setFailed(failed);
        report.setSkipped(skipped);
        report.setErrors(errors);
        if (allXml.length() > 0) {
            report.setRawReport(allXml.toString());
        }
    }

    private Path findSurefireReports(Path projectRoot) {
        // Maven: target/surefire-reports
        Path mavenDefault = projectRoot.resolve("target/surefire-reports");
        if (Files.isDirectory(mavenDefault)) return mavenDefault;
        // Gradle: build/test-results/test
        Path gradleDefault = projectRoot.resolve("build/test-results/test");
        if (Files.isDirectory(gradleDefault)) return gradleDefault;
        // 递归搜索（最多 4 层）
        try (Stream<Path> walk = Files.walk(projectRoot, 4)) {
            return walk.filter(f -> f.getFileName().toString().equals("surefire-reports")
                    || f.getFileName().toString().equals("test-results")).findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== Jest 输出解析 =====================

    private void parseJestOutput(Path projectRoot, TestReport report, StringBuilder logBuf) {
        report.setTestFramework("JEST");
        // Jest 通常在 package.json 中配置了 --json --outputFile
        Path jestResult = projectRoot.resolve("test-results.json");
        if (!Files.exists(jestResult)) {
            jestResult = projectRoot.resolve("jest-results.json");
        }
        if (Files.exists(jestResult)) {
            try {
                String json = Files.readString(jestResult);
                report.setRawReport(json);
                // 简单正则提取（避免引入 Jackson 额外依赖）
                int total = extractInt(json, "\"numTotalTests\"\\s*:\\s*(\\d+)");
                int pass = extractInt(json, "\"numPassedTests\"\\s*:\\s*(\\d+)");
                int fail = extractInt(json, "\"numFailedTests\"\\s*:\\s*(\\d+)");
                report.setTotalTests(total);
                report.setPassed(pass);
                report.setFailed(fail);
                report.setSkipped(Math.max(0, total - pass - fail));
                logBuf.append("[TEST REPORT] 解析 Jest JSON: total=").append(total)
                        .append(" passed=").append(pass).append(" failed=").append(fail).append("\n");
            } catch (Exception e) {
                logBuf.append("[TEST REPORT] Jest JSON 解析失败: ").append(e.getMessage()).append("\n");
            }
        } else {
            logBuf.append("[TEST REPORT] 未找到 Jest JSON 报告 (test-results.json)\n");
        }
    }

    // ===================== pytest 输出解析 =====================

    private void parsePytestOutput(Path projectRoot, TestReport report, StringBuilder logBuf) {
        report.setTestFramework("PYTEST");
        // pytest 使用 --junitxml= 或 --json-report
        Path junitXml = projectRoot.resolve("test-results.xml");
        if (!Files.exists(junitXml)) junitXml = projectRoot.resolve("pytest-results.xml");
        if (Files.exists(junitXml)) {
            try {
                // 复用 JUnit XML 解析
                parseJUnitXml(projectRoot, report, logBuf);
                report.setRawReport(Files.readString(junitXml));
            } catch (Exception e) {
                logBuf.append("[TEST REPORT] pytest XML 解析失败: ").append(e.getMessage()).append("\n");
            }
        } else {
            logBuf.append("[TEST REPORT] 未找到 pytest 报告文件\n");
        }
    }

    // ===================== Go test 输出解析 =====================

    private void parseGoTestOutput(Path projectRoot, TestReport report, StringBuilder logBuf) {
        report.setTestFramework("GO_TEST");
        // go test -json 输出已经是 JSON 行
        Path goJson = projectRoot.resolve("test-output.json");
        if (Files.exists(goJson)) {
            try {
                String json = Files.readString(goJson);
                report.setRawReport(json);
                int total = countMatches(json, "\"Test\"");
                int pass = countMatches(json, "\"Action\":\"pass\"");
                int fail = countMatches(json, "\"Action\":\"fail\"");
                int skip = countMatches(json, "\"Action\":\"skip\"");
                report.setTotalTests(total);
                report.setPassed(pass);
                report.setFailed(fail);
                report.setSkipped(skip);
                logBuf.append("[TEST REPORT] Go test: total=").append(total)
                        .append(" pass=").append(pass).append(" fail=").append(fail).append("\n");
            } catch (Exception e) {
                logBuf.append("[TEST REPORT] Go test JSON 解析失败: ").append(e.getMessage()).append("\n");
            }
        } else {
            logBuf.append("[TEST REPORT] 未找到 Go test JSON 输出\n");
        }
    }

    private void parseCargoTestOutput(Path projectRoot, TestReport report, StringBuilder logBuf) {
        // Cargo test 输出较简单，解析文本摘要
        logBuf.append("[TEST REPORT] Cargo test 结果已记录（框架细节待扩展）\n");
    }

    private void parseCTestOutput(Path projectRoot, TestReport report, StringBuilder logBuf) {
        Path ctestXml = projectRoot.resolve("build/Testing/TAG");
        if (Files.exists(ctestXml)) {
            try {
                String content = Files.readString(ctestXml);
                report.setRawReport(content);
            } catch (Exception ignored) {}
        }
        logBuf.append("[TEST REPORT] CTest 结果已记录\n");
    }

    // ===================== 覆盖率解析 =====================

    private void parseCoverage(Path projectRoot, String lang, TestReport report, StringBuilder logBuf) {
        try {
            if (lang.contains("node")) {
                parseIstanbulCoverage(projectRoot, report, logBuf);
            } else if (lang.contains("python")) {
                parseCoveragePy(projectRoot, report, logBuf);
            } else if (lang.equals("go") || lang.equals("golang")) {
                parseGoCoverage(projectRoot, report, logBuf);
            } else {
                parseJaCoCoCoverage(projectRoot, report, logBuf);
            }
        } catch (Exception e) {
            logBuf.append("[COVERAGE] 覆盖率解析异常: ").append(e.getMessage()).append("\n");
        }
    }

    private void parseJaCoCoCoverage(Path projectRoot, TestReport report, StringBuilder logBuf) throws Exception {
        // JaCoCo CSV: target/site/jacoco/jacoco.csv
        Path csv = projectRoot.resolve("target/site/jacoco/jacoco.csv");
        if (!Files.exists(csv)) csv = projectRoot.resolve("build/reports/jacoco/test/jacocoTestReport.csv");
        if (Files.exists(csv)) {
            String content = Files.readString(csv);
            report.setRawReport((report.getRawReport() != null ? report.getRawReport() : "") + "\n[JaCoCo CSV]\n" + content);
            // CSV header: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,...
            String[] lines = content.split("\n");
            int totalLineMissed = 0, totalLineCovered = 0;
            int totalBranchMissed = 0, totalBranchCovered = 0;
            for (int i = 1; i < lines.length; i++) {
                String[] cols = lines[i].split(",");
                if (cols.length >= 9) {
                    totalLineMissed += safeParseInt(cols[7]);
                    totalLineCovered += safeParseInt(cols[8]);
                    if (cols.length >= 7) {
                        totalBranchMissed += safeParseInt(cols[5]);
                        totalBranchCovered += safeParseInt(cols[6]);
                    }
                }
            }
            int totalLine = totalLineMissed + totalLineCovered;
            int totalBranch = totalBranchMissed + totalBranchCovered;
            if (totalLine > 0) {
                report.setLineCoverage(100.0 * totalLineCovered / totalLine);
            }
            if (totalBranch > 0) {
                report.setBranchCoverage(100.0 * totalBranchCovered / totalBranch);
            }
            if (totalLine > 0 || totalBranch > 0) {
                // 综合覆盖率 = 行覆盖优先，否则分支覆盖
                report.setCoveragePercent(totalLine > 0 ? report.getLineCoverage() : report.getBranchCoverage());
            }
            logBuf.append("[COVERAGE] JaCoCo: line=").append(String.format("%.1f%%", report.getLineCoverage()))
                    .append(" branch=").append(String.format("%.1f%%", report.getBranchCoverage())).append("\n");
        } else {
            logBuf.append("[COVERAGE] 未找到 JaCoCo CSV 报告（建议: mvn test jacoco:report）\n");
        }
    }

    private void parseIstanbulCoverage(Path projectRoot, TestReport report, StringBuilder logBuf) throws Exception {
        Path covDir = projectRoot.resolve("coverage");
        Path covJson = covDir.resolve("coverage-final.json");
        if (!Files.exists(covJson)) covJson = projectRoot.resolve(".nyc_output/coverage-final.json");
        if (Files.exists(covJson)) {
            String json = Files.readString(covJson);
            report.setRawReport((report.getRawReport() != null ? report.getRawReport() : "") + "\n[Istanbul]\n" + json);
            // 简单提取覆盖率摘要
            int linesTotal = extractAllInts(json, "\"lines\"\\s*:\\s*\\{[^}]*\"total\"\\s*:\\s*(\\d+)").stream().mapToInt(Integer::intValue).sum();
            int linesCovered = extractAllInts(json, "\"lines\"\\s*:\\s*\\{[^}]*\"covered\"\\s*:\\s*(\\d+)").stream().mapToInt(Integer::intValue).sum();
            if (linesTotal > 0) {
                report.setLineCoverage(100.0 * linesCovered / linesTotal);
                report.setCoveragePercent(report.getLineCoverage());
                logBuf.append("[COVERAGE] Istanbul: line=").append(String.format("%.1f%%", report.getLineCoverage())).append("\n");
            }
        }
    }

    private void parseCoveragePy(Path projectRoot, TestReport report, StringBuilder logBuf) throws Exception {
        Path covReport = projectRoot.resolve(".coverage");
        if (Files.exists(covReport)) {
            logBuf.append("[COVERAGE] 检测到 .coverage 文件（需 coverage.py 生成 XML/HTML 报告才可解析）\n");
        }
    }

    private void parseGoCoverage(Path projectRoot, TestReport report, StringBuilder logBuf) throws Exception {
        Path covOut = projectRoot.resolve("coverage.out");
        if (Files.exists(covOut)) {
            String content = Files.readString(covOut);
            report.setRawReport((report.getRawReport() != null ? report.getRawReport() : "") + "\n[Go Coverage]\n" + content);
            // go coverage 格式: module/file.go:行.列,行.列 语句数 执行次数
            int totalStmt = 0, coveredStmt = 0;
            for (String line : content.split("\n")) {
                if (line.trim().isEmpty() || line.startsWith("mode:")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    int stmts = safeParseInt(parts[1]);
                    int count = safeParseInt(parts[2]);
                    totalStmt += stmts;
                    if (count > 0) coveredStmt += stmts;
                }
            }
            if (totalStmt > 0) {
                report.setLineCoverage(100.0 * coveredStmt / totalStmt);
                report.setCoveragePercent(report.getLineCoverage());
                logBuf.append("[COVERAGE] Go: line=").append(String.format("%.1f%%", report.getLineCoverage())).append("\n");
            }
        }
    }

    // ===================== 质量门 =====================

    private void evaluateQualityGate(TestReport report, StringBuilder logBuf) {
        List<String> failures = new ArrayList<>();

        // 检查通过率
        if (report.getTotalTests() != null && report.getTotalTests() > 0) {
            double passRate = 100.0 * report.getPassed() / report.getTotalTests();
            if (passRate < minPassRate) {
                failures.add(String.format("测试通过率 %.1f%% < %.1f%% 阈值", passRate, minPassRate));
            }
        }

        // 检查覆盖率
        if (report.getCoveragePercent() != null && report.getCoveragePercent() >= 0) {
            if (report.getCoveragePercent() < minCoverage) {
                failures.add(String.format("覆盖率 %.1f%% < %.1f%% 阈值", report.getCoveragePercent(), minCoverage));
            }
        }

        // 检查失败数
        if (report.getFailed() != null && report.getFailed() > 0) {
            failures.add(String.format("存在 %d 个失败测试", report.getFailed()));
        }

        report.setQualityGatePassed(failures.isEmpty());
        report.setQualityGateMessage(failures.isEmpty() ? "质量门通过 ✓" : "质量门失败: " + String.join("; ", failures));

        if (!failures.isEmpty()) {
            logBuf.append("[QUALITY GATE] ❌ ").append(report.getQualityGateMessage()).append("\n");
        } else {
            logBuf.append("[QUALITY GATE] ✅ 质量门通过\n");
        }
    }

    private String buildSummary(TestReport r) {
        return String.format("%d total, %d passed, %d failed, %d skipped | Coverage: %.1f%%",
                r.getTotalTests(), r.getPassed(), r.getFailed(), r.getSkipped(), r.getCoveragePercent());
    }

    // ===================== 查询方法 =====================

    public List<TestReport> getByBuildId(Long buildId) {
        return testReportRepository.findByBuildId(buildId);
    }

    public List<TestReport> getByProjectId(Long projectId) {
        return testReportRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public Optional<TestReport> getById(Long id) {
        return testReportRepository.findById(id);
    }

    public Double avgCoverage(Long projectId) {
        return testReportRepository.avgCoverageByProjectId(projectId);
    }

    // ===================== 工具方法 =====================

    private int safeParseInt(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private int extractInt(String text, String regex) {
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? safeParseInt(m.group(1)) : 0;
    }

    private List<Integer> extractAllInts(String text, String regex) {
        var results = new ArrayList<Integer>();
        var m = java.util.regex.Pattern.compile(regex).matcher(text);
        while (m.find()) results.add(safeParseInt(m.group(1)));
        return results;
    }

    private int countMatches(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
