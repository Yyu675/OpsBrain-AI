package com.devops.agent.domain.alert.service;

import com.devops.agent.domain.alert.DTO.AlertWebSocketEvent;
import com.devops.agent.domain.alert.entity.Alert;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警 WebSocket 推送服务（非阻塞旁路）
 * <p>
 * 三事件类型：NEW（新告警入库）/ UPDATE（去重递增次数）/ RESOLVED（已恢复）。
 * 推送失败仅记 WARN 日志，绝不抛异常——告警主流程（入库/建单）不受推送影响。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-15
 */
@Slf4j
@Service
public class AlertWebSocketNotifier {

    /** 已连接会话表（sessionId → session），ConcurrentHashMap 保证并发安全 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 会话管理（由 Handler 调用） ====================

    public void addSession(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("🔌 [AlertWS] 新增连接 | sessionId={} | 在线数={}", session.getId(), sessions.size());
    }

    public void removeSession(WebSocketSession session) {
        WebSocketSession removed = sessions.remove(session.getId());
        if (removed != null) {
            log.info("📴 [AlertWS] 连接断开 | sessionId={} | 在线数={}", session.getId(), sessions.size());
        }
    }

    // ==================== 广播（非阻塞旁路） ====================

    /**
     * 广播新告警（NEW）
     * <p>推送失败不影响主流程。无客户端连接时静默跳过（DEBUG）。</p>
     */
    public void broadcastNew(Alert alert) {
        broadcast(AlertWebSocketEvent.of("NEW", alert));
    }

    /**
     * 广播重复告警（UPDATE，去重递增次数）
     */
    public void broadcastUpdate(Alert alert) {
        broadcast(AlertWebSocketEvent.of("UPDATE", alert));
    }

    /**
     * 广播告警恢复（RESOLVED）
     */
    public void broadcastResolved(Alert alert) {
        broadcast(AlertWebSocketEvent.of("RESOLVED", alert));
    }

    public int onlineCount() {
        return sessions.size();
    }

    // ==================== 内部实现 ====================

    /**
     * 序列化并广播给所有在线会话。
     * <p>
     * 核心契约：任何异常仅记 WARN 并继续，禁止向上抛出——推送是附属增值，
     * 不能让某条连接的问题影响告警持久化主链路。
     * </p>
     */
    private void broadcast(AlertWebSocketEvent event) {
        if (sessions.isEmpty()) {
            log.debug("ℹ️ [AlertWS] 无在线客户端，跳过广播 | type={} | alertName={}",
                    event.getType(), event.getAlert() != null ? event.getAlert().getAlertName() : "?");
            return;
        }

        TextMessage message;
        try {
            message = new TextMessage(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            // 序列化失败不可能影响主流程——多数场景是告警字段含极端内容
            log.warn("⚠️ [AlertWS] 序列化失败，跳过广播 | type={} | error={}", event.getType(), e.getMessage());
            return;
        }

        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(message);
                    }
                }
            } catch (IOException | IllegalStateException e) {
                log.warn("⚠️ [AlertWS] 推送失败 | sessionId={} | type={} | error={}",
                        session.getId(), event.getType(), e.getMessage());
            }
        }
    }
}