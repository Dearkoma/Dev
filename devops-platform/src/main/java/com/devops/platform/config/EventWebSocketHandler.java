package com.devops.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局事件推送 WebSocket Handler (原生 WS, 无 STOMP)
 *
 * 端点: /ws/events
 * 协议: 服务器单向推送 JSON 文本帧
 *   { "type": "deployment.updated", "payload": {...}, "ts": 1234567890 }
 *   { "type": "notification.created", "payload": {...}, "ts": 1234567890 }
 *   { "type": "build.updated", "payload": {...}, "ts": 1234567890 }
 *
 * 前端收到后通过 eventBus 转发到对应组件, 替代 15s 轮询。
 */
@Slf4j
@Component
public class EventWebSocketHandler extends TextWebSocketHandler {

    private static final Set<WebSocketSession> SESSIONS = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.add(session);
        log.info("[WS] 事件推送客户端连接: {}, 总数={}", session.getId(), SESSIONS.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session);
        log.info("[WS] 事件推送客户端断开: {}, 剩余={}", session.getId(), SESSIONS.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        SESSIONS.remove(session);
        // 客户端刷新页面/关标签页/HMR 时会异常断连, 此时服务端可能仍在写帧,
        // 抛出 IOException(Broken pipe / Connection reset) 或 ClosedChannelException(其 message 为 null)。
        // 这类属于正常现象, 降级为 debug, 避免 "err=null" 之类的告警刷屏误导排查。
        if (isClientDisconnect(exception)) {
            log.debug("[WS] 客户端已断开, 忽略传输错误: {}, {} (剩余={})",
                    session.getId(), describe(exception), SESSIONS.size());
        } else {
            log.warn("[WS] 事件推送传输错误: {}, {} (剩余={})",
                    session.getId(), describe(exception), SESSIONS.size());
        }
    }

    /** 判断是否为客户端主动断开引发的传输异常 (含被包装的 cause) */
    private static boolean isClientDisconnect(Throwable e) {
        if (e == null) return true;
        // ClosedChannelException 是 IOException 的子类, message 通常为 null
        if (e instanceof IOException) return true;
        Throwable cause = e.getCause();
        return cause != null && cause != e && isClientDisconnect(cause);
    }

    /** 异常描述: message 为空时回退到类名, 避免打出 "err=null" */
    private static String describe(Throwable e) {
        if (e == null) return "unknown";
        String msg = e.getMessage();
        return (msg == null || msg.isBlank())
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + msg;
    }

    /**
     * 向所有在线客户端广播事件，自动附带 traceId 实现全链路串联
     * @param type 事件类型 (deployment.updated / notification.created / build.updated / ...)
     * @param payload 事件负载 (任意可 JSON 序列化对象)
     */
    public static void broadcast(String type, Object payload) {
        if (SESSIONS.isEmpty()) return;
        try {
            String traceId = TraceIdFilter.TraceContext.getTraceId();
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("type", type);
            event.put("payload", payload == null ? Map.of() : payload);
            event.put("ts", System.currentTimeMillis());
            if (traceId != null) {
                event.put("traceId", traceId);
            }
            String text = MAPPER.writeValueAsString(event);
            TextMessage msg = new TextMessage(text);
            SESSIONS.removeIf(s -> !s.isOpen());
            for (WebSocketSession s : SESSIONS) {
                try {
                    synchronized (s) {
                        s.sendMessage(msg);
                    }
                } catch (Exception e) {
                    // 广播是尽力而为: 客户端中途断开导致写失败很常见, 降级为 debug。
                    // 注意这里必须捕获 Exception 而非仅 IOException —— Tomcat 的
                    // WritePendingException 是 RuntimeException, 漏接会冒泡到外层
                    // 被误报成 "序列化事件失败", 并中断后续 session 的推送。
                    log.debug("[WS] 推送失败 session={}: {}", s.getId(), describe(e));
                    SESSIONS.remove(s);
                }
            }
        } catch (Exception e) {
            log.error("[WS] 序列化事件失败 type={}", type, e);
        }
    }

    public static int onlineCount() {
        return SESSIONS.size();
    }
}
