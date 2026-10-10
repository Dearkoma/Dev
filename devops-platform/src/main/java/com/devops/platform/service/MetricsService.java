package com.devops.platform.service;

import com.devops.platform.aspect.HttpMetricsAspect;
import com.devops.platform.entity.HttpMetric;
import com.devops.platform.repository.HttpMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * APM 指标聚合 - 计算 QPS/错误率/响应时间分位数
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final HttpMetricRepository metricRepository;
    private final HttpMetricsAspect aspect;

    /** 实时 QPS 估算 (用最近 60s 总数 / 60) */
    public Map<String, Object> realtime() {
        long total = aspect.getTotalRequests();
        long errors = aspect.getTotalErrors();
        LocalDateTime since = LocalDateTime.now().minusMinutes(1);
        List<HttpMetric> recent = metricRepository.findByRecordedAtAfterOrderByRecordedAtDesc(since);

        double qps = recent.size() / 60.0;
        long errorCount = recent.stream().filter(m -> m.getStatus() >= 400).count();
        double errorRate = recent.isEmpty() ? 0 : (errorCount * 100.0 / recent.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequests", total);
        result.put("totalErrors", errors);
        result.put("qps", Math.round(qps * 100) / 100.0);
        result.put("errorRate", Math.round(errorRate * 100) / 100.0);
        result.put("activeRecentCount", recent.size());
        return result;
    }

    /** 最近 N 分钟的 QPS/错误率/响应时间 时序 */
    public Map<String, Object> timeSeries(int minutes) {
        if (minutes <= 0 || minutes > 1440) minutes = 60;
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<HttpMetric> all = metricRepository.findByRecordedAtAfterOrderByRecordedAtDesc(since);
        if (all.isEmpty()) return emptyTimeSeries(minutes);

        // 按分钟分桶
        Map<String, List<HttpMetric>> buckets = all.stream()
                .collect(Collectors.groupingBy(m -> m.getRecordedAt().withSecond(0).withNano(0).toString()));

        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = minutes - 1; i >= 0; i--) {
            LocalDateTime t = LocalDateTime.now().minusMinutes(i).withSecond(0).withNano(0);
            String key = t.toString();
            List<HttpMetric> bucket = buckets.getOrDefault(key, List.of());
            points.add(buildPoint(t, bucket));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interval", "1m");
        result.put("minutes", minutes);
        result.put("points", points);
        return result;
    }

    /** 慢路径 Top N (按 P95 倒序) */
    public List<Map<String, Object>> topSlowPaths(int limit, int minutes) {
        if (limit <= 0 || limit > 50) limit = 10;
        if (minutes <= 0 || minutes > 1440) minutes = 60;
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<HttpMetric> all = metricRepository.findByRecordedAtAfterOrderByRecordedAtDesc(since);
        Map<String, List<HttpMetric>> byPath = all.stream()
                .collect(Collectors.groupingBy(HttpMetric::getPath));

        return byPath.entrySet().stream()
                .filter(e -> e.getValue().size() >= 3)  // 至少 3 次请求
                .map(e -> {
                    List<Long> durs = e.getValue().stream().map(HttpMetric::getDurationMs).sorted().toList();
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("path", e.getKey());
                    p.put("count", durs.size());
                    p.put("p50", percentile(durs, 50));
                    p.put("p95", percentile(durs, 95));
                    p.put("p99", percentile(durs, 99));
                    p.put("avg", durs.stream().mapToLong(Long::longValue).sum() / durs.size());
                    p.put("errorCount", e.getValue().stream().filter(m -> m.getStatus() >= 400).count());
                    return p;
                })
                .sorted((a, b) -> Long.compare((long) b.get("p95"), (long) a.get("p95")))
                .limit(limit)
                .toList();
    }

    /** 错误路径 - 错误数倒序 */
    public List<Map<String, Object>> topErrorPaths(int limit, int minutes) {
        if (limit <= 0 || limit > 50) limit = 10;
        if (minutes <= 0 || minutes > 1440) minutes = 60;
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<HttpMetric> all = metricRepository.findByRecordedAtAfterOrderByRecordedAtDesc(since);
        Map<String, Long> errorsByPath = all.stream()
                .filter(m -> m.getStatus() >= 400)
                .collect(Collectors.groupingBy(HttpMetric::getPath, Collectors.counting()));

        return errorsByPath.entrySet().stream()
                .map(e -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("path", e.getKey());
                    p.put("errorCount", e.getValue());
                    return p;
                })
                .sorted((a, b) -> Long.compare((long) b.get("errorCount"), (long) a.get("errorCount")))
                .limit(limit)
                .toList();
    }

    private Map<String, Object> buildPoint(LocalDateTime t, List<HttpMetric> bucket) {
        long count = bucket.size();
        long errors = bucket.stream().filter(m -> m.getStatus() >= 400).count();
        List<Long> durs = bucket.stream().map(HttpMetric::getDurationMs).sorted().toList();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("time", t.toString());
        p.put("count", count);
        p.put("errors", errors);
        p.put("errorRate", count == 0 ? 0 : Math.round(errors * 10000.0 / count) / 100.0);
        p.put("p50", durs.isEmpty() ? 0 : percentile(durs, 50));
        p.put("p95", durs.isEmpty() ? 0 : percentile(durs, 95));
        p.put("p99", durs.isEmpty() ? 0 : percentile(durs, 99));
        p.put("qps", Math.round(count / 60.0 * 100) / 100.0);
        return p;
    }

    private long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    private Map<String, Object> emptyTimeSeries(int minutes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interval", "1m");
        result.put("minutes", minutes);
        result.put("points", List.of());
        return result;
    }
}
