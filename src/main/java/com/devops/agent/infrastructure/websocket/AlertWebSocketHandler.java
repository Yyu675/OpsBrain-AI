package com.devops.agent.infrastructure.websocket;

import com.devops.agent.domain.alert.service.AlertWebSocketNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 告警实时推送 WebSocket Handler（/ws/alerts）
 * <p>
 * 前端 AlertStreamMode 连接本端点后，接收 NEW / UPDATE / RESOLVED 三类告警事件。
 * 只读通道：忽略所有客户端上行文本，仅做会话登记与广播。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-15
 */
@Slf4j
@Component
public class AlertWebSocketHandler extends TextWebSocketHandler {

    private final AlertWebSocketNotifier notifier;

    public AlertWebSocketHandler(AlertWebSocketNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        notifier.addSession(session);
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        notifier.removeSession(session);
        super.afterConnectionClosed(session, status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 推送通道是单向广播，忽略客户端上行文本
        log.debug("ℹ️ [AlertWS] 忽略客户端上行消息 | sessionId={} | payload={}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        // 传输错误仅记 WARN，交由框架触发 afterConnectionClosed 清理会话
        log.warn("⚠️ [AlertWS] 传输错误 | sessionId={} | error={}", session.getId(), exception.getMessage());
        notifier.removeSession(session);
        // 不调用 super —— 避免重复关闭已在错误路径的会话
    }
}