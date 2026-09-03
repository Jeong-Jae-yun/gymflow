package com.gymflow.global.websocket;

import com.gymflow.global.security.jwt.JwtTokenProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final Long USER_ID = 1L;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private SimpleMeterRegistry meterRegistry;
    private WebSocketMetrics webSocketMetrics;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        webSocketMetrics = new WebSocketMetrics(meterRegistry);
        interceptor = new StompAuthChannelInterceptor(jwtTokenProvider, webSocketMetrics);

        lenient().when(jwtTokenProvider.validateToken(VALID_TOKEN)).thenReturn(true);
        lenient().when(jwtTokenProvider.getUserId(VALID_TOKEN)).thenReturn(USER_ID);
    }

    private double connectFailedCount() {
        return meterRegistry.get("gymflow_websocket_connect_failed_total").counter().count();
    }

    private Message<byte[]> connectMessage(String bearerToken) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (bearerToken != null) {
            accessor.setNativeHeader("Authorization", bearerToken);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> nonConnectMessage(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        accessor.setSessionId("session-1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("유효한 JWT로 CONNECT하면 connect_failed는 증가하지 않는다")
    void preSend_WithValidJwtConnect_ShouldNotIncrementConnectFailed() {
        Message<byte[]> message = connectMessage("Bearer " + VALID_TOKEN);

        assertThatCode(() -> interceptor.preSend(message, null)).doesNotThrowAnyException();

        assertThat(connectFailedCount()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("유효하지 않은 JWT로 CONNECT하면 기존 MessagingException이 발생하고 connect_failed가 1 증가한다")
    void preSend_WithInvalidJwtConnect_ShouldThrowAndIncrementConnectFailed() {
        when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);
        Message<byte[]> message = connectMessage("Bearer invalid-token");

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessage("유효하지 않은 WebSocket 인증 정보입니다.");

        assertThat(connectFailedCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Authorization 헤더가 없는 CONNECT는 MessagingException이 발생하고 connect_failed가 1 증가한다")
    void preSend_WithoutAuthorizationHeader_ShouldThrowAndIncrementConnectFailed() {
        Message<byte[]> message = connectMessage(null);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class)
                .hasMessage("유효하지 않은 WebSocket 인증 정보입니다.");

        assertThat(connectFailedCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CONNECT가 아닌 STOMP frame은 인증 검증 대상이 아니며 connect_failed도 증가하지 않는다")
    void preSend_WithNonConnectFrame_ShouldNotIncrementConnectFailed() {
        Message<byte[]> message = nonConnectMessage(StompCommand.SEND);

        assertThatCode(() -> interceptor.preSend(message, null)).doesNotThrowAnyException();

        assertThat(connectFailedCount()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("정상 인증 이후의 DISCONNECT frame은 connect_failed를 증가시키지 않는다")
    void preSend_WithDisconnectFrame_ShouldNotIncrementConnectFailed() {
        Message<byte[]> message = nonConnectMessage(StompCommand.DISCONNECT);

        assertThatCode(() -> interceptor.preSend(message, null)).doesNotThrowAnyException();

        assertThat(connectFailedCount()).isEqualTo(0.0);
    }
}
