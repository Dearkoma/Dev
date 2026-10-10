package com.devops.platform.controller;

import com.devops.platform.entity.Project;
import com.devops.platform.entity.SecurityReport;
import com.devops.platform.entity.SecurityVulnerability;
import com.devops.platform.repository.ProjectRepository;
import com.devops.platform.repository.SecurityReportRepository;
import com.devops.platform.repository.SecurityVulnerabilityRepository;
import com.devops.platform.service.ProjectAccessService;
import com.devops.platform.service.SecurityScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 安全扫描 API
 *  - GET    /api/security/reports/project/{id}             项目所有报告
 *  - GET    /api/security/reports/{id}                     单个报告
 *  - GET    /api/security/reports/build/{id}               某次构建的所有报告
 *  - GET    /api/security/vulnerabilities/{reportId}       报告下的所有漏洞
 *  - GET    /api/security/vulnerabilities/project/{id}      项目下所有 OPEN 漏洞
 *  - PUT    /api/security/vulnerabilities/{id}/status      改漏洞状态(标记已修复/忽略)
 *  - GET    /api/security/trivy-available                  检查 Trivy 是否可用
 *  - POST   /api/security/scan/fs/{projectId}              手动触发 FS 扫描
 *  - POST   /api/security/scan/dep/{projectId}             手动触发依赖扫描
 *  - GET    /api/security/stats/project/{id}               项目安全统计
 */
@Slf4j
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecurityController {

    private final SecurityReportRepository reportRepository;
    private final SecurityVulnerabilityRepository vulnerabilityRepository;
    private final SecurityScanService scanService;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService accessService;

    @Value("${devops.pipeline.workspace-dir:./data/workspace}")
    private String workspaceDir;

    @GetMapping("/trivy-available")
    public ResponseEntity<Map<String, Object>> trivyAvailable() {
        // 修复: 原来在 Map.of 里调用了两次 isTrivyAvailable(), 白白多跑一次 trivy --version
        boolean available = scanService.isTrivyAvailable();
        return ResponseEntity.ok(Map.of(
                "available", available,
                "message", available
                        ? "Trivy 可用"
                        : "Trivy 未安装,安全扫描将降级为 ERRORED 状态"));
    }

    /**
     * 查询本地漏洞库状态 (版本/上次更新时间/下次过期时间)
     */
    @GetMapping("/db-status")
    public ResponseEntity<Map<String, Object>> dbStatus() {
        return ResponseEntity.ok(scanService.getDatabaseStatus());
    }

    /**
     * 手动更新漏洞库 (在线下载最新漏洞库, 可能耗时数分钟)
     * body 可选: {"includeJavaDb": true} — 是否同时更新 Java 依赖漏洞库 (默认 true)
     */
    @PostMapping("/db-update")
    public ResponseEntity<Map<String, Object>> dbUpdate(
            @RequestBody(required = false) Map<String, Object> params) {
        boolean includeJavaDb = params == null
                || !Boolean.FALSE.equals(params.get("includeJavaDb"));
        log.info("手动更新漏洞库请求: includeJavaDb={}", includeJavaDb);
        Map<String, Object> result = scanService.updateDatabase(includeJavaDb);
        result.put("dbStatus", scanService.getDatabaseStatus());
        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发 FS 扫描（扫描项目的代码/配置文件）
     */
    @PostMapping("/scan/fs/{projectId}")
    public ResponseEntity<Map<String, Object>> triggerFsScan(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Integer> params) {
        return triggerScan("FS", projectId, params);
    }

    /**
     * 手动触发依赖扫描（扫描项目的依赖文件: pom.xml / package.json 等）
     */
    @PostMapping("/scan/dep/{projectId}")
    public ResponseEntity<Map<String, Object>> triggerDepScan(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Integer> params) {
        return triggerScan("DEPENDENCY", projectId, params);
    }

    private ResponseEntity<Map<String, Object>> triggerScan(
            String scanType, Long projectId, Map<String, Integer> params) {

        try {
            return doTriggerScan(scanType, projectId, params);
        } catch (Exception e) {
            log.error("手动扫描异常: scanType={}, projectId={}, error={}",
                    scanType, projectId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "扫描异常: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> doTriggerScan(
            String scanType, Long projectId, Map<String, Integer> params) throws IOException {

        accessService.assertCanAccessProject(projectId);

        if (!scanService.isTrivyAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Trivy 未安装，无法执行扫描"));
        }

        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "项目不存在: " + projectId));
        }

        Path target = resolveProjectPath(project);

        int th = params != null ? params.getOrDefault("thresholdHigh", 5) : 5;
        int tc = params != null ? params.getOrDefault("thresholdCritical", 0) : 0;

        log.info("手动触发 {} 扫描: projectId={}, target={}, th={}, tc={}",
                scanType, projectId, target, th, tc);

        SecurityReport report;
        if ("DEPENDENCY".equals(scanType)) {
            report = scanService.scanDependency(null, projectId, target, th, tc);
        } else {
            report = scanService.scanFilesystem(null, projectId, target, th, tc);
        }

        return ResponseEntity.ok(Map.of(
                "success", !SecurityScanService.STATUS_ERRORED.equals(report.getStatus()),
                "reportId", report.getId(),
                "status", report.getStatus(),
                "summary", report.getSummary(),
                "totalCount", report.getTotalCount(),
                "criticalCount", report.getCriticalCount(),
                "highCount", report.getHighCount(),
                "costMs", report.getCostMs()));
    }

    /** 解析项目在 workspace 中的实际路径 */
    private Path resolveProjectPath(Project project) throws IOException {
        String code = project.getCode();
        if (code == null || code.isBlank()) {
            throw new IOException("项目编码 (code) 为空");
        }
        Path root = Paths.get(workspaceDir).toAbsolutePath().normalize();
        Path projectDir = root.resolve(code);
        if (!Files.exists(projectDir)) {
            throw new IOException("项目目录不存在: " + projectDir);
        }
        // 检查一级子目录中是否有构建标记文件（支持 monorepo checkout 结构）
        String[] markers = {"pom.xml", "package.json", "Dockerfile",
                "CMakeLists.txt", "Cargo.toml", "go.mod"};
        for (String marker : markers) {
            if (Files.exists(projectDir.resolve(marker))) {
                return projectDir;
            }
        }
        try (var stream = Files.list(projectDir)) {
            for (Path child : stream.toList()) {
                if (!Files.isDirectory(child)) continue;
                for (String marker : markers) {
                    if (Files.exists(child.resolve(marker))) {
                        return child;
                    }
                }
            }
        }
        return projectDir;  // fallback: 即使没有构建标记也扫整个目录
    }

    @GetMapping("/reports/project/{projectId}")
    public List<SecurityReport> reportsByProject(@PathVariable Long projectId) {
        accessService.assertCanAccessProject(projectId);
        return reportRepository.findByProjectIdOrderByScannedAtDesc(projectId);
    }

    @GetMapping("/reports/build/{buildId}")
    public List<SecurityReport> reportsByBuild(@PathVariable Long buildId) {
        accessService.assertCanAccessBuild(buildId);
        return reportRepository.findByBuildIdOrderByScannedAtDesc(buildId);
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<SecurityReport> getReport(@PathVariable Long id) {
        accessService.assertCanAccessSecurityReport(id);
        return reportRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/vulnerabilities/{reportId}")
    public List<SecurityVulnerability> vulnerabilitiesByReport(@PathVariable Long reportId) {
        accessService.assertCanAccessSecurityReport(reportId);
        return vulnerabilityRepository.findByReportIdOrderBySeverityAsc(reportId);
    }

    @GetMapping("/vulnerabilities/project/{projectId}")
    public List<SecurityVulnerability> vulnerabilitiesByProject(@PathVariable Long projectId) {
        accessService.assertCanAccessProject(projectId);
        return vulnerabilityRepository.findByProjectIdAndStatusOrderByDiscoveredAtDesc(
                projectId, SecurityVulnerability.STATUS_OPEN);
    }

    @PutMapping("/vulnerabilities/{id}/status")
    public ResponseEntity<SecurityVulnerability> updateVulnStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        String note = body.get("note");
        SecurityVulnerability existing = vulnerabilityRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        accessService.assertCanAccessProject(existing.getProjectId());
        existing.setStatus(status != null ? status : SecurityVulnerability.STATUS_OPEN);
        existing.setResolveNote(note);
        existing.setResolvedAt(java.time.LocalDateTime.now());
        return ResponseEntity.ok(vulnerabilityRepository.save(existing));
    }

    @GetMapping("/stats/project/{projectId}")
    public ResponseEntity<Map<String, Object>> statsByProject(@PathVariable Long projectId) {
        accessService.assertCanAccessProject(projectId);
        List<SecurityReport> reports = reportRepository.findLatestByProject(projectId);
        SecurityReport latest = reports.isEmpty() ? null : reports.get(0);
        long openCount = vulnerabilityRepository.countByProjectIdAndStatus(
                projectId, SecurityVulnerability.STATUS_OPEN);
        long totalScans = reports.size();
        long failedScans = reports.stream()
                .filter(r -> SecurityScanService.STATUS_FAILED.equals(r.getStatus()))
                .count();

        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalScans", totalScans);
        stats.put("failedScans", failedScans);
        stats.put("openVulnerabilities", openCount);
        stats.put("latestReport", latest);
        return ResponseEntity.ok(stats);
    }
}
