package com.devops.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 全链路 traceId + spanId 过滤器 — 分布式追踪上下文传播
 *
 * W3C Trace Context 兼容：
 *  - traceparent: 00-{traceId}-{parentSpanId}-{traceFlags}
 *  - tracestate: 厂商扩展
 *
 * 每个 HTTP 请求生成/继承 traceId 和 spanId，放入 MDC。
 * 响应头返回 X-Trace-Id，方便前端/调用方串联。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String SPAN_ID_MDC_KEY = "spanId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = null;
        String parentSpanId = null;

        // 1. 尝试从 W3C traceparent 头解析
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        if (traceparent != null && traceparent.length() >= 55) {
            // 格式: 00-{32hex traceId}-{16hex spanId}-{2hex flags}
            String[] parts = traceparent.split("-");
            if (parts.length >= 3) {
                traceId = parts[1];
                parentSpanId = parts[2];
            }
        }

        // 2. 兜底: X-Trace-Id
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(TRACE_ID_HEADER);
        }

        // 3. 生成新的
        if (traceId == null || traceId.isBlank()) {
            traceId = generateTraceId();
        }

        // 生成当前 span ID
        String spanId = generateSpanId();

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        MDC.put(SPAN_ID_MDC_KEY, spanId);

        // 响应头
        response.setHeader(TRACE_ID_HEADER, traceId);
        response.setHeader(SPAN_ID_HEADER, spanId);
        // 返回 W3C traceparent 给调用方
        response.setHeader(TRACEPARENT_HEADER, "00-" + traceId + "-" + spanId + "-01");

        // 存储到线程上下文，供 EventWebSocketHandler 等非请求线程使用
        TraceContext.set(traceId, spanId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
            MDC.remove(SPAN_ID_MDC_KEY);
            TraceContext.clear();
        }
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    private String generateSpanId() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }

    /**
     * 线程本地 Trace 上下文 — 跨线程传播 traceId/spanId
     */
    public static class TraceContext {
        private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
        private static final ThreadLocal<String> SPAN_ID = new ThreadLocal<>();

        public static void set(String traceId, String spanId) {
            TRACE_ID.set(traceId);
            SPAN_ID.set(spanId);
        }

        public static String getTraceId() {
            return TRACE_ID.get();
        }

        public static String getSpanId() {
            return SPAN_ID.get();
        }

        public static void clear() {
            TRACE_ID.remove();
            SPAN_ID.remove();
        }
    }
}
