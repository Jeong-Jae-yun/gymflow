package com.gymflow.global.websocket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private WebSocketMetrics webSocketMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        webSocketMetrics = new WebSocketMetrics(meterRegistry);
    }

    private SessionConnectedEvent connectedEvent(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(sessionId);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionConnectedEvent(this, message);
    }

    private SessionDisconnectEvent disconnectEvent(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, sessionId, CloseStatus.NORMAL);
    }

    private double activeConnections() {
        return meterRegistry.get("gymflow_websocket_active_connections").gauge().value();
    }

    @Test
    @DisplayName("SessionConnectedEvent를 받으면 active_connections가 1 증가한다")
    void onSessionConnected_ShouldIncrementActiveConnections() {
        webSocketMetrics.onSessionConnected(connectedEvent("session-1"));

        assertThat(activeConnections()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SessionDisconnectEvent를 받으면 active_connections가 1 감소한다")
    void onSessionDisconnect_ShouldDecrementActiveConnections() {
        webSocketMetrics.onSessionConnected(connectedEvent("session-1"));
        webSocketMetrics.onSessionDisconnect(disconnectEvent("session-1"));

        assertThat(activeConnections()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("서로 다른 session이 여러 개 연결되면 정확한 현재 개수를 반영한다")
    void multipleSessions_ShouldReflectAccurateCount() {
        webSocketMetrics.onSessionConnected(connectedEvent("session-1"));
        webSocketMetrics.onSessionConnected(connectedEvent("session-2"));
        webSocketMetrics.onSessionConnected(connectedEvent("session-3"));
        webSocketMetrics.onSessionDisconnect(disconnectEvent("session-2"));

        assertThat(activeConnections()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("동일 session에 대해 SessionConnectedEvent가 중복 발생해도 두 번 증가하지 않는다")
    void duplicateConnectedEvent_ShouldNotDoubleCount() {
        webSocketMetrics.onSessionConnected(connectedEvent("session-1"));
        webSocketMetrics.onSessionConnected(connectedEvent("session-1"));

        assertThat(activeConnections()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("동일 session에 대해 SessionDisconnectEvent가 중복 발생해도 음수가 되지 않는다")
    void duplicateDisconnectEvent_ShouldNotGoNegative() {
        webSocketMetrics.onSessionConnected(connectedEvent("session-1"));
        webSocketMetrics.onSessionDisconnect(disconnectEvent("session-1"));
        webSocketMetrics.onSessionDisconnect(disconnectEvent("session-1"));

        assertThat(activeConnections()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("connect 없이 disconnect만 발생해도 음수가 되지 않는다")
    void disconnectWithoutPriorConnect_ShouldNotGoNegative() {
        webSocketMetrics.onSessionDisconnect(disconnectEvent("session-unknown"));

        assertThat(activeConnections()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("recordNotificationSent/Failed/recordConnectFailed는 각각 독립적으로 증가한다")
    void counters_ShouldBeIndependent() {
        webSocketMetrics.recordNotificationSent();
        webSocketMetrics.recordNotificationSent();
        webSocketMetrics.recordNotificationFailed();
        webSocketMetrics.recordConnectFailed();
        webSocketMetrics.recordConnectFailed();
        webSocketMetrics.recordConnectFailed();

        assertThat(meterRegistry.get("gymflow_websocket_notification_sent_total").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("gymflow_websocket_notification_failed_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_websocket_connect_failed_total").counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("모든 metric에는 sessionId 등 고카디널리티 tag가 없다")
    void metrics_ShouldHaveNoHighCardinalityTags() {
        webSocketMetrics.onSessionConnected(connectedEvent("session-1"));
        webSocketMetrics.recordNotificationSent();
        webSocketMetrics.recordNotificationFailed();
        webSocketMetrics.recordConnectFailed();

        assertThat(meterRegistry.get("gymflow_websocket_active_connections").gauge().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_websocket_notification_sent_total").counter().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_websocket_notification_failed_total").counter().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_websocket_connect_failed_total").counter().getId().getTags()).isEmpty();
    }
}
