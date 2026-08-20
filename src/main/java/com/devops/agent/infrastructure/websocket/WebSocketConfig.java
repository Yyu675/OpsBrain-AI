package com.devops.agent.infrastructure.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册配置
 * <p>
 * 注册告警实时推送端点 {@code /ws/alerts}（L2 阶段 2）：
 * 前端 AlertStreamMode 连接后接收 NEW / UPDATE / RESOLVED 三类告警事件。
 * 推送是只读旁路通道——客户端仅订阅，注册端点失败不应影响告警主流程。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-15
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AlertWebSocketHandler alertWebSocketHandler;

    public WebSocketConfig(AlertWebSocketHandler alertWebSocketHandler) {
        this.alertWebSocketHandler = alertWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(alertWebSocketHandler, "/ws/alerts")
                .setAllowedOriginPatterns("*");
    }
}