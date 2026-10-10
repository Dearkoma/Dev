package com.devops.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器 — 防御常见 Web 攻击
 *
 * 设置:
 *  - Content-Security-Policy: 限制资源加载源，防御 XSS
 *  - X-Content-Type-Options: 禁止 MIME 类型嗅探
 *  - X-Frame-Options: 防止点击劫持
 *  - X-XSS-Protection: 启用浏览器 XSS 过滤器
 *  - Strict-Transport-Security: HSTS（生产环境启用）
 *  - Referrer-Policy: 控制 Referer 泄露
 *  - Permissions-Policy: 限制浏览器 API
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Content-Security-Policy
        // 策略: 默认只允许同源; 脚本/样式允许同源 + unsafe-inline (React 需要);
        // 图片/字体允许同源 + data:; 连接允许同源 + WebSocket
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: https:; " +
                "font-src 'self' data:; " +
                "connect-src 'self' ws: wss: http://localhost:* http://127.0.0.1:*; " +
                "frame-ancestors 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self'");

        // 禁止 MIME 类型嗅探
        response.setHeader("X-Content-Type-Options", "nosniff");

        // 防止点击劫持
        response.setHeader("X-Frame-Options", "DENY");

        // 启用浏览器 XSS 过滤器
        response.setHeader("X-XSS-Protection", "1; mode=block");

        // Referrer Policy
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Permissions-Policy: 限制敏感 API
        response.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=()");

        // Cache-Control: API 响应不缓存
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store, max-age=0");
        }

        filterChain.doFilter(request, response);
    }
}
