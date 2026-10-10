package com.devops.platform.controller;

import com.devops.platform.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * APM 性能指标 API
 *  - GET /api/metrics/realtime                  实时 QPS/错误率/总数
 *  - GET /api/metrics/timeseries?minutes=60     时序数据 (前端画图)
 *  - GET /api/metrics/slow-paths?limit=10       Top N 慢路径
 *  - GET /api/metrics/error-paths?limit=10      Top N 错误路径
 *  - GET /api/metrics/prometheus                Prometheus 格式导出
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;
    private final com.devops.platform.repository.HttpMetricRepository metricRepository;

    @GetMapping("/realtime")
    public Map<String, Object> realtime() {
        return metricsService.realtime();
    }

    @GetMapping("/timeseries")
    public Map<String, Object> timeseries(@RequestParam(defaultValue = "60") int minutes) {
        return metricsService.timeSeries(minutes);
    }

    @GetMapping("/slow-paths")
    public java.util.List<Map<String, Object>> slowPaths(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "60") int minutes) {
        return metricsService.topSlowPaths(limit, minutes);
    }

    @GetMapping("/error-paths")
    public java.util.List<Map<String, Object>> errorPaths(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "60") int minutes) {
        return metricsService.topErrorPaths(limit, minutes);
    }

    /** Prometheus 文本格式导出 (用于接入外部 Prometheus/Grafana) */
    @GetMapping(value = "/prometheus", produces = "text/plain; charset=utf-8")
    public String prometheus() {
        Map<String, Object> rt = metricsService.realtime();
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP devops_http_requests_total Total HTTP requests since service start\n");
        sb.append("# TYPE devops_http_requests_total counter\n");
        sb.append("devops_http_requests_total ").append(rt.get("totalRequests")).append("\n");
        sb.append("# HELP devops_http_errors_total Total HTTP errors since service start\n");
        sb.append("# TYPE devops_http_errors_total counter\n");
        sb.append("devops_http_errors_total ").append(rt.get("totalErrors")).append("\n");
        sb.append("# HELP devops_http_qps Current QPS (last 1 minute average)\n");
        sb.append("# TYPE devops_http_qps gauge\n");
        sb.append("devops_http_qps ").append(rt.get("qps")).append("\n");
        sb.append("# HELP devops_http_error_rate_percent Current error rate percent (last 1 minute)\n");
        sb.append("# TYPE devops_http_error_rate_percent gauge\n");
        sb.append("devops_http_error_rate_percent ").append(rt.get("errorRate")).append("\n");
        return sb.toString();
    }

    /** 每天凌晨 3 点清理 7 天前的指标数据 */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldMetrics() {
        try {
            int deleted = metricRepository.deleteOlderThan(java.time.LocalDateTime.now().minusDays(7));
            System.out.println("[Metrics] 清理 " + deleted + " 条 7 天前指标");
        } catch (Exception e) {
            System.err.println("[Metrics] 清理失败: " + e.getMessage());
        }
    }
}
