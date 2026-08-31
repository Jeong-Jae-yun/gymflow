package com.gymflow.domain.waitingqueue.domain.websocket;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueEventPublisher;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueuePromotedEvent;
import com.gymflow.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 실제 Testcontainers Redis + 실제 내장 서버(RANDOM_PORT) + 실제 STOMP Client를 이용해
 * WaitingQueue 승급 이벤트가 Redis Pub/Sub -> WaitingQueueEventSubscriber ->
 * SimpMessagingTemplate -> 실제 사용자 STOMP 세션까지 전달되는지 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "websocket.allowed-origins=http://localhost:5173")
class WaitingQueueWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WaitingQueueEventPublisher waitingQueueEventPublisher;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("WaitingQueue 승급 이벤트는 Redis Pub/Sub을 거쳐 해당 사용자의 STOMP 세션으로 실시간 전달된다")
    void promotedEvent_ShouldBeDeliveredToSubscribedUserOverStomp() throws Exception {
        // given
        Long userId = 777L;
        String token = jwtTokenProvider.createAccessToken(userId, "ws-test@gymflow.com", UserRole.USER);

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();

        StompSession session = stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);

        session.subscribe("/user/queue/waiting-queue", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedMessages.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });

        WaitingQueuePromotedEvent event = new WaitingQueuePromotedEvent(
                500L, 1L, userId, 101L,
                LocalDateTime.of(2026, 8, 20, 14, 0),
                LocalDateTime.of(2026, 8, 20, 14, 15),
                LocalDateTime.now());

        // when & then: 구독이 서버에 반영되기까지의 시간차를 감안해 전달될 때까지 재발행한다
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            waitingQueueEventPublisher.publish(event);
            String received = receivedMessages.poll(500, TimeUnit.MILLISECONDS);
            assertThat(received).isNotNull();
            assertThat(received).contains("\"promotionId\":500");
            assertThat(received).contains("\"userId\":" + userId);
            assertThat(received).contains("\"waitingQueueId\":1");
        });

        session.disconnect();
    }

    @Test
    @DisplayName("유효하지 않은 토큰으로 연결을 시도하면 STOMP 연결이 거부된다")
    void connect_WithInvalidToken_ShouldBeRejected() {
        // given
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer invalid-token");

        // when
        CompletableFuture<StompSession> future = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                });

        // then
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("브라우저의 Vite dev 서버 Origin(localhost:5173)에서 온 handshake는 허용된다")
    void handshake_WithConfiguredDevOrigin_ShouldBeAccepted() throws Exception {
        // given: 실제 브라우저는 handshake HTTP 요청에 Origin 헤더를 반드시 실어 보낸다.
        // 이 헤더가 websocket.allowed-origins(FRONTEND_ORIGIN)와 일치해야 handshake가 통과한다.
        Long userId = 778L;
        String token = jwtTokenProvider.createAccessToken(userId, "ws-origin-ok@gymflow.com", UserRole.USER);

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", "http://localhost:5173");

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        // when
        StompSession session = stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        handshakeHeaders,
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);

        // then
        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    @DisplayName("허용되지 않은 Origin에서 온 handshake는 403으로 거부된다")
    void handshake_WithDisallowedOrigin_ShouldBeRejected() {
        // given
        Long userId = 779L;
        String token = jwtTokenProvider.createAccessToken(userId, "ws-origin-bad@gymflow.com", UserRole.USER);

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", "http://evil.example.com");

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        // when
        CompletableFuture<StompSession> future = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {
                });

        // then
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS));
    }
}
