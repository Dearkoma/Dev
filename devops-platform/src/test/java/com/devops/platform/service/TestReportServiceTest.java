package com.devops.platform.service;

import com.devops.platform.entity.TestReport;
import com.devops.platform.repository.TestReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TestReportService 单元测试 — 测试报告解析与质量门禁
 */
class TestReportServiceTest {

    private TestReportService testReportService;
    private TestReportRepository testReportRepository;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        testReportRepository = mock(TestReportRepository.class);
        when(testReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        testReportService = new TestReportService(testReportRepository);
        ReflectionTestUtils.setField(testReportService, "minPassRate", 80.0);
        ReflectionTestUtils.setField(testReportService, "minCoverage", 60.0);
    }

    @Test
    void testParseJUnitXml() throws Exception {
        // 创建 Maven surefire 目录结构
        Path surefireDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        // 写入一个 JUnit XML 报告
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.MyTest" tests="10" failures="1" errors="0" skipped="2" time="0.5">
                <testcase name="testPass" classname="com.example.MyTest" time="0.1"/>
                <testcase name="testFail" classname="com.example.MyTest" time="0.1">
                    <failure message="expected: true but was: false"/>
                </testcase>
            </testsuite>
            """;
        Files.writeString(surefireDir.resolve("TEST-com.example.MyTest.xml"), xml);

        StringBuilder logBuf = new StringBuilder();
        TestReport report = testReportService.parseAndSave(
                tempDir, "java", 1L, 1L, 1L, 5000L, logBuf);

        assertNotNull(report);
        assertEquals("JUNIT", report.getTestFramework());
        assertEquals(10, report.getTotalTests());
        assertEquals(7, report.getPassed());  // 10 - 1 - 0 - 2 = 7
        assertEquals(1, report.getFailed());
        assertEquals(2, report.getSkipped());
        assertEquals(0, report.getErrors());
        assertEquals(1L, report.getBuildId());
        assertEquals(5000L, report.getDurationMs());
        assertNotNull(report.getRawReport());
        assertTrue(logBuf.toString().contains("JUnit"));
    }

    @Test
    void testQualityGatePassesWithGoodResults() throws Exception {
        Path surefireDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);
        String xml = """
            <testsuite name="AllTests" tests="100" failures="0" errors="0" skipped="5" time="2.0"/>
            """;
        Files.writeString(surefireDir.resolve("TEST-AllTests.xml"), xml);

        StringBuilder logBuf = new StringBuilder();
        TestReport report = testReportService.parseAndSave(
                tempDir, "java", 1L, 1L, 1L, 3000L, logBuf);

        assertTrue(report.getQualityGatePassed());
        assertTrue(report.getQualityGateMessage().contains("通过"));
        assertEquals(100, report.getTotalTests());
        assertEquals(95, report.getPassed());
    }

    @Test
    void testQualityGateFailsWithLowPassRate() throws Exception {
        Path surefireDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);
        String xml = """
            <testsuite name="AllTests" tests="100" failures="50" errors="0" skipped="0" time="2.0"/>
            """;
        Files.writeString(surefireDir.resolve("TEST-AllTests.xml"), xml);

        StringBuilder logBuf = new StringBuilder();
        TestReport report = testReportService.parseAndSave(
                tempDir, "java", 1L, 1L, 1L, 3000L, logBuf);

        assertFalse(report.getQualityGatePassed());
        assertTrue(report.getQualityGateMessage().contains("失败"));
    }

    @Test
    void testNoTestReportsFound() throws Exception {
        StringBuilder logBuf = new StringBuilder();
        TestReport report = testReportService.parseAndSave(
                tempDir, "java", 1L, 1L, 1L, 1000L, logBuf);

        assertNotNull(report);
        assertEquals("JUNIT", report.getTestFramework());
        assertEquals(0, report.getTotalTests());
        assertTrue(report.getQualityGatePassed()); // 无测试时门禁放行
    }

    @Test
    void testGoTestFrameworkDetection() throws Exception {
        // Go 项目不生成 XML 报告，但框架应正确识别
        StringBuilder logBuf = new StringBuilder();
        TestReport report = testReportService.parseAndSave(
                tempDir, "go", 1L, 1L, 1L, 2000L, logBuf);

        assertEquals("GO_TEST", report.getTestFramework());
    }

    @Test
    void testNodeProjectUsesJestFramework() throws Exception {
        StringBuilder logBuf = new StringBuilder();
        TestReport report = testReportService.parseAndSave(
                tempDir, "node", 1L, 1L, 1L, 2000L, logBuf);

        assertEquals("JEST", report.getTestFramework());
    }

    @Test
    void testPythonProjectUsesPytest() throws Exception {
        StringBuilder logBuf = new StringBuilder();
        TestReport report = testReportService.parseAndSave(
                tempDir, "python", 1L, 1L, 1L, 2000L, logBuf);

        assertEquals("PYTEST", report.getTestFramework());
    }
}
