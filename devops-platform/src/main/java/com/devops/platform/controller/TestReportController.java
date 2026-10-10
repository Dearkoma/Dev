package com.devops.platform.controller;

import com.devops.platform.entity.TestReport;
import com.devops.platform.service.ProjectAccessService;
import com.devops.platform.service.TestReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test-reports")
public class TestReportController {

    private final TestReportService testReportService;
    private final ProjectAccessService accessService;

    public TestReportController(TestReportService testReportService, ProjectAccessService accessService) {
        this.testReportService = testReportService;
        this.accessService = accessService;
    }

    /** 按构建 ID 查询测试报告 */
    @GetMapping("/build/{buildId}")
    public ResponseEntity<List<TestReport>> getByBuildId(@PathVariable Long buildId) {
        accessService.assertCanAccessBuild(buildId);
        return ResponseEntity.ok(testReportService.getByBuildId(buildId));
    }

    /** 按项目 ID 查询测试报告 */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<TestReport>> getByProjectId(@PathVariable Long projectId) {
        accessService.assertCanAccessProject(projectId);
        return ResponseEntity.ok(testReportService.getByProjectId(projectId));
    }

    /** 获取单个报告详情（含原始 XML/JSON） */
    @GetMapping("/{id}")
    public ResponseEntity<TestReport> getById(@PathVariable Long id) {
        accessService.assertCanAccessTestReport(id);
        return testReportService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 项目平均覆盖率 */
    @GetMapping("/project/{projectId}/avg-coverage")
    public ResponseEntity<Map<String, Object>> getAvgCoverage(@PathVariable Long projectId) {
        accessService.assertCanAccessProject(projectId);
        Double cov = testReportService.avgCoverage(projectId);
        return ResponseEntity.ok(Map.of("projectId", projectId, "avgCoverage", cov != null ? cov : -1.0));
    }
}
