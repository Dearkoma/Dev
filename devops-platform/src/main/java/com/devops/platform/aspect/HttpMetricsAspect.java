package com.devops.platform.aspect;

import com.devops.platform.entity.HttpMetric;
import com.devops.platform.repository.HttpMetricRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP 请求性能监控 - 异步落库 + 内存计数器
 * <p>
 * 双轨:
 *  1) 内存计数 (atomic) - 实时 QPS, 永远存在, 用于 /metrics/realtime
 *  2) DB 落库 (MetricsRecorder 单独 bean) - 时序分析
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class HttpMetricsAspect {

    private final HttpMetricRepository metricRepository;
    private final MetricsRecorder recorder;

    /** 实时累计请求数 (整个服务生命周期) */
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();

    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        int status = 200;
        String errorDetail = null;
        try {
            Object result = pjp.proceed();
            status = getResponseStatus();
            return result;
        } catch (Throwable t) {
            status = getResponseStatus();
            if (status < 400) status = 500;
            errorDetail = t.getClass().getSimpleName() + ": " + truncate(t.getMessage(), 200);
            throw t;
        } finally {
            long duration = System.currentTimeMillis() - start;
            totalRequests.increment();
            if (status >= 400) totalErrors.increment();
            // 调用外部 bean 触发 @Async (同类内 @Async 不生效)
            recorder.recordAsync(pjp, start, status, duration, errorDetail);
        }
    }

    private int getResponseStatus() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return 200;
            int s = attrs.getResponse().getStatus();
            return s > 0 ? s : 200;
        } catch (Exception e) {
            return 200;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    public long getTotalRequests() { return totalRequests.sum(); }
    public long getTotalErrors() { return totalErrors.sum(); }
}
