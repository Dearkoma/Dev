package com.devops.platform.aspect;

import com.devops.platform.entity.HttpMetric;
import com.devops.platform.repository.HttpMetricRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 指标落库器 - 独立 bean, 让 @Async 生效
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsRecorder {

    private final HttpMetricRepository metricRepository;

    @Async
    public void recordAsync(ProceedingJoinPoint pjp, long start, int status, long duration, String errorDetail) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest req = attrs.getRequest();
            String path = normalizePath(req.getRequestURI());

            HttpMetric m = new HttpMetric();
            m.setMethod(req.getMethod());
            m.setPath(path);
            m.setStatus(status);
            m.setDurationMs(duration);
            m.setClientIp(clientIp(req));
            m.setUserAgent(truncate(req.getHeader("User-Agent"), 256));

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                m.setUsername(auth.getName());
            }
            metricRepository.save(m);
        } catch (Exception e) {
            log.debug("记录指标失败: {}", e.getMessage());
        }
    }

    /** 路径归一化: /api/projects/123 → /api/projects/{id} - 避免 ID 维度爆炸 */
    private String normalizePath(String uri) {
        if (uri == null) return "";
        String n = uri.replaceAll("/\\d+", "/{id}");
        n = n.replaceAll("/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", "/{uuid}");
        return n.length() > 256 ? n.substring(0, 256) : n;
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
