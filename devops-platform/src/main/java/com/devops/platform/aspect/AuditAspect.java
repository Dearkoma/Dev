package com.devops.platform.aspect;

import com.devops.platform.entity.AuditLog;
import com.devops.platform.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.*;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 审计注解 */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audit {
        String action();    // CREATE / UPDATE / DELETE / TRIGGER / CANCEL / APPROVE / REJECT / LOGIN
        String resource();  // PROJECT / PIPELINE / BUILD / ENVIRONMENT / USER / DEPLOYMENT
    }

    @Around("@annotation(audit)")
    public Object audit(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction(audit.action());
        auditLog.setResource(audit.resource());
        auditLog.setCreatedAt(LocalDateTime.now());

        // 获取用户名
        String username = getUsername(joinPoint);
        auditLog.setUsername(username != null ? username : "anonymous");

        // 获取 IP
        auditLog.setIpAddress(getClientIp());

        // 获取参数中的资源 ID 和名称
        extractResourceInfo(joinPoint, auditLog);

        try {
            Object result = joinPoint.proceed();
            auditLog.setSuccess(true);
            // 新增类操作的资源 ID 只有执行完才有，从返回值补一次
            fillIdFromResult(result, auditLog);
            auditLogRepository.save(auditLog);
            return result;
        } catch (Exception e) {
            auditLog.setSuccess(false);
            auditLog.setErrorMessage(e.getMessage());
            auditLogRepository.save(auditLog);
            throw e;
        }
    }

    /**
     * 从返回值里补资源 ID。新增操作的目标对象在进入方法时还不存在 ID，
     * 因此在方法成功返回后取一次返回值的主键。
     */
    private void fillIdFromResult(Object result, AuditLog auditLog) {
        if (auditLog.getResourceId() != null || result == null) {
            return;
        }
        Object body = result;
        if (result instanceof org.springframework.http.ResponseEntity<?> re) {
            body = re.getBody();
        }
        if (body == null || body instanceof java.util.Map || body instanceof String) {
            return;
        }
        try {
            Object v = body.getClass().getMethod("getId").invoke(body);
            if (v instanceof Number n) {
                auditLog.setResourceId(n.longValue());
            }
        } catch (Exception ignored) {
            // 返回值没有主键就保持为空，不影响审计记录本身
        }
    }

    private String getUsername(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof Principal) {
                return ((Principal) arg).getName();
            }
        }
        // 尝试从 SecurityContext 获取（跳过匿名认证）
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {}
        // 登录/注册这类发生在认证之前的接口，用户名在请求体里
        for (Object arg : joinPoint.getArgs()) {
            if (arg == null || arg instanceof Principal) {
                continue;
            }
            try {
                Object v = arg.getClass().getMethod("getUsername").invoke(arg);
                if (v instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (Exception ignored) {
                // 该参数没有 getUsername，继续看下一个
            }
        }
        return "system";
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String xf = request.getHeader("X-Forwarded-For");
                if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
                return request.getRemoteAddr();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private void extractResourceInfo(ProceedingJoinPoint joinPoint, AuditLog auditLog) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            String name = paramNames[i];
            Object val = args[i];
            if (val == null) continue;

            // 资源 ID
            if (("id".equals(name) || name.endsWith("Id")) && val instanceof Long) {
                auditLog.setResourceId((Long) val);
            }
            // 详细参数
            if (name.equals("data") || name.equals("project") || name.equals("pipeline")
                    || name.equals("env") || name.equals("environment") || name.equals("body")) {
                try {
                    Map<String, Object> map = objectMapper.convertValue(val, Map.class);
                    if (map.containsKey("name")) {
                        auditLog.setResourceName((String) map.get("name"));
                    }
                    auditLog.setDetail(objectMapper.writeValueAsString(map));
                } catch (Exception ignored) {}
            }
        }
    }
}
