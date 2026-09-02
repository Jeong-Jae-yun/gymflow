package com.gymflow.global.websocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketMetrics {

    private final Set<String> activeSessionIds = ConcurrentHashMap.newKeySet();

    private final Counter notificationSentCounter;
    private final Counter notificationFailedCounter;
    private final Counter connectFailedCounter;

    public WebSocketMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("gymflow_websocket_active_connections", activeSessionIds, Set::size)
                .description("CONNECTED가 확정된 현재 STOMP session 수")
                .register(meterRegistry);
        this.notificationSentCounter = Counter.builder("gymflow_websocket_notification_sent_total")
                .description("서버가 WebSocket/STOMP notification 전송 호출(convertAndSendToUser)을 예외 없이 "
                        + "수행한 횟수. Simple Broker 구조상 클라이언트의 실제 수신을 보장하는 "
                        + "delivery acknowledgment가 아니다.")
                .register(meterRegistry);
        this.notificationFailedCounter = Counter.builder("gymflow_websocket_notification_failed_total")
                .description("WebSocket/STOMP notification 전송 호출이 예외로 실패한 횟수")
                .register(meterRegistry);
        this.connectFailedCounter = Counter.builder("gymflow_websocket_connect_failed_total")
                .description("STOMP CONNECT 프레임의 JWT 인증에 실패한 횟수")
                .register(meterRegistry);
    }

    @EventListener
    public void onSessionConnected(SessionConnectedEvent event) {
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (sessionId != null) {
            activeSessionIds.add(sessionId);
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        activeSessionIds.remove(event.getSessionId());
    }

    public void recordNotificationSent() {
        notificationSentCounter.increment();
    }

    public void recordNotificationFailed() {
        notificationFailedCounter.increment();
    }

    public void recordConnectFailed() {
        connectFailedCounter.increment();
    }
}
