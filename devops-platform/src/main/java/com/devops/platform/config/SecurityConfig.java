package com.devops.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Map;

/**
 * Spring Security 配置 - JWT 无状态认证 + 四级角色 RBAC
 * <p>
 * 角色层级：
 *   ADMIN     — 全部操作，含用户管理和审计日志
 *   MANAGER   — 项目/流水线/环境/模板/部署审批/定时任务 管理
 *   DEVELOPER — 触发构建、申请部署、查看资源
 *   VIEWER    — 只读，禁止任何写操作
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CSRF: Cookie-based token for SPA (XSRF-TOKEN cookie → X-XSRF-TOKEN header)
        // Webhook/认证/WS 端点豁免 CSRF（登录是新会话首个请求，浏览器还没有 XSRF-TOKEN cookie，无法通过校验）
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/");

        // 强制加载 CSRF Token：让每次响应都写入 XSRF-TOKEN cookie，
        // 否则登录后的第一个 POST 之前 cookie 不会被下发，后续写操作会全部 403
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler() {
            @Override
            public void handle(jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response,
                               java.util.function.Supplier<org.springframework.security.web.csrf.CsrfToken> csrfToken) {
                csrfToken.get();  // 强制加载 → SaveOnAccess 立即写入 cookie
                super.handle(request, response, csrfToken);
            }
        };

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers("/api/auth/**", "/api/webhook/**", "/ws/**", "/actuator/**")
            )
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(res.getWriter(),
                        Map.of("error", "未登录或 Token 已过期，请重新登录"));
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(res.getWriter(),
                        Map.of("error", "权限不足，无法访问该资源"));
                })
            )
            .authorizeHttpRequests(auth -> auth
                // ========== 公开接口 ==========
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/webhook/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/ws").permitAll()
                // actuator 健康检查: 公开 (给 K8s livenessProbe 用)
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // actuator 其他端点: ADMIN
                .requestMatchers("/actuator/**").hasRole("ADMIN")

                // ========== ADMIN 专属 ==========
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/audit/**").hasRole("ADMIN")

                // ========== 工具链检测 / 下载 ==========
                // 查询语言能力与工具链状态: 所有登录用户 (新建项目表单需要展示可用性)
                .requestMatchers(HttpMethod.GET, "/api/toolchain/**").authenticated()
                // 下载并安装工具链: 系统级操作 (会写入 tools/ 目录), ADMIN + MANAGER
                .requestMatchers(HttpMethod.POST, "/api/toolchain/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 构建操作 ==========
                // 触发构建: ADMIN + MANAGER + DEVELOPER
                .requestMatchers(HttpMethod.POST, "/api/builds/trigger").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")
                // 审批门通过/拒绝: ADMIN + MANAGER
                .requestMatchers(HttpMethod.POST, "/api/builds/*/approve-step", "/api/builds/*/reject-step").hasAnyRole("ADMIN", "MANAGER")
                // 断点续跑: 同触发构建
                .requestMatchers(HttpMethod.POST, "/api/builds/*/retry").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")
                // 取消/删除构建: ADMIN + MANAGER
                .requestMatchers(HttpMethod.DELETE, "/api/builds/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 部署操作 ==========
                .requestMatchers(HttpMethod.POST, "/api/deployments/request").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")
                // 审批/回滚/删除: 授权下沉到 DeploymentService.canApproveDeployment(),
                // 它同时支持「全局 ADMIN/MANAGER」与「项目 OWNER/MAINTAINER」两种身份。
                // 若在此硬编码 hasAnyRole(ADMIN,MANAGER), 会与 /api/projects/{id}/my-role 返回的
                // canApprove 判定冲突 —— 项目 OWNER 前端能看到审批/回滚/删除按钮, 点击却被 403 拦掉。
                // 此处只要求已登录, 具体授权由 Service 层逐个入口校验(approve/reject/rollback/delete)。
                .requestMatchers("/api/deployments/approve/**", "/api/deployments/reject/**",
                                 "/api/deployments/rollback/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/deployments/**").authenticated()

                // ========== 服务实例管理: 所有变更 ADMIN + MANAGER ==========
                .requestMatchers(HttpMethod.POST, "/api/instances/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/instances/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/instances/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 项目 (test-db-connection 允许 DEVELOPER) ==========
                .requestMatchers(HttpMethod.POST, "/api/projects/test-db-connection").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")
                .requestMatchers(HttpMethod.POST, "/api/projects/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/projects/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/projects/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 流水线 / 环境 / 模板: 全部写操作 ADMIN + MANAGER ==========
                .requestMatchers(HttpMethod.POST, "/api/pipelines/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/pipelines/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/pipelines/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/environments/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/environments/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/environments/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/templates/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/templates/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/templates/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 构建变量 (可能含密钥): 写操作 ADMIN + MANAGER ==========
                .requestMatchers(HttpMethod.POST, "/api/variables/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/variables/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/variables/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== Git 集成 (创建 MR 允许 DEVELOPER) ==========
                .requestMatchers(HttpMethod.POST, "/api/git/**").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")

                // ========== Webhook 订阅管理: 写操作 ADMIN + MANAGER ==========
                .requestMatchers(HttpMethod.POST, "/api/webhooks/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/webhooks/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/webhooks/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 高级能力: 写操作 ADMIN + MANAGER ==========
                .requestMatchers(HttpMethod.POST, "/api/advanced/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/advanced/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/advanced/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 安全扫描: 触发扫描 DEVELOPER+, 漏洞处置 ADMIN+MANAGER ==========
                .requestMatchers(HttpMethod.POST, "/api/security/scan/**").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")
                .requestMatchers(HttpMethod.PUT, "/api/security/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 制品删除: ADMIN + MANAGER ==========
                .requestMatchers(HttpMethod.DELETE, "/api/artifacts/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 通知删除: ADMIN + MANAGER ==========
                .requestMatchers(HttpMethod.DELETE, "/api/notifications/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 定时任务: ADMIN + MANAGER ==========
                .requestMatchers("/api/scheduler/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 其余 API: 只要登录即可 (读操作, 对象级授权见 Service 层) ==========
                .requestMatchers("/api/**").authenticated()

                // ========== 静态资源: 公开 ==========
                .anyRequest().permitAll()
            )
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        // 使用具体的允许域名而非通配符（credentials + "*" 的组合是 CORS 规范禁止的）
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*"
        ));
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CorsFilter corsFilter() {
        return new CorsFilter(corsConfigurationSource());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
