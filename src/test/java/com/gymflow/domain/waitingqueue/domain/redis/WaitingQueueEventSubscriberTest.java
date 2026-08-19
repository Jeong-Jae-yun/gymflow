package com.gymflow.domain.waitingqueue.domain.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class WaitingQueueEventSubscriberTest {

    private static final String CHANNEL = WaitingQueueEventChannel.WAITING_QUEUE_EVENT;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WaitingQueueEventSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new WaitingQueueEventSubscriber(new RedisMessageListenerContainer(), objectMapper);
    }

    private Message message(String body) {
        return new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("올바른 JSON 메시지를 수신하면 예외 없이 이벤트로 역직렬화한다")
    void onMessage_WithValidJson_ShouldDeserializeWithoutException() {
        // given
        WaitingQueuePromotedEvent event = new WaitingQueuePromotedEvent(
                10L, 3L, 101L,
                LocalDateTime.of(2026, 8, 20, 14, 0),
                LocalDateTime.of(2026, 8, 20, 14, 15),
                LocalDateTime.of(2026, 8, 20, 13, 58));
        String json = objectMapper.writeValueAsString(event);

        // when & then
        assertThatCode(() -> subscriber.onMessage(message(json), null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("잘못된 형식의 JSON을 수신해도 애플리케이션이 죽지 않고 예외 없이 처리된다")
    void onMessage_WithMalformedJson_ShouldNotThrow() {
        // when & then
        assertThatCode(() -> subscriber.onMessage(message("{invalid-json"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("무관한 형식의 JSON 메시지를 수신해도 예외 없이 무시된다")
    void onMessage_WithUnrelatedJsonShape_ShouldNotThrow() {
        // when & then
        assertThatCode(() -> subscriber.onMessage(message("{\"foo\":\"bar\"}"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("메시지 본문이 비어 있으면 아무 것도 하지 않고 예외 없이 종료된다")
    void onMessage_WithEmptyBody_ShouldBeIgnored() {
        // given
        Message emptyMessage = new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), new byte[0]);

        // when & then
        assertThatCode(() -> subscriber.onMessage(emptyMessage, null)).doesNotThrowAnyException();
    }
}
