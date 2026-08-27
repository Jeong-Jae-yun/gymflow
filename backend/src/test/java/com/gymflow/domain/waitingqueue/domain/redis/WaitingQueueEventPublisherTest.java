package com.gymflow.domain.waitingqueue.domain.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WaitingQueueEventPublisherTest {

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 20, 14, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 20, 14, 15);
    private static final LocalDateTime PROMOTED_AT = LocalDateTime.of(2026, 8, 20, 13, 58);

    @Mock
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WaitingQueueEventPublisher waitingQueueEventPublisher;

    @BeforeEach
    void setUp() {
        waitingQueueEventPublisher = new WaitingQueueEventPublisher(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("승급 이벤트를 gymflow:event:waiting-queue Channel로 JSON 직렬화하여 발행한다")
    void publish_ShouldSendSerializedJsonToWaitingQueueEventChannel() {
        // given
        WaitingQueuePromotedEvent event = new WaitingQueuePromotedEvent(500L, 10L, 3L, 101L, START_AT, END_AT, PROMOTED_AT);

        // when
        waitingQueueEventPublisher.publish(event);

        // then
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(channelCaptor.capture(), payloadCaptor.capture());

        assertThat(channelCaptor.getValue()).isEqualTo(WaitingQueueEventChannel.WAITING_QUEUE_EVENT);

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"promotionId\":500");
        assertThat(payload).contains("\"waitingQueueId\":10");
        assertThat(payload).contains("\"userId\":3");
        assertThat(payload).contains("\"resourceId\":101");
        assertThat(payload).contains("\"startAt\"");
        assertThat(payload).contains("\"endAt\"");
        assertThat(payload).contains("\"promotedAt\"");

        WaitingQueuePromotedEvent deserialized = objectMapper.readValue(payload, WaitingQueuePromotedEvent.class);
        assertThat(deserialized).isEqualTo(event);
    }

    @Test
    @DisplayName("서로 다른 이벤트는 서로 다른 JSON payload로 직렬화되어 발행된다")
    void publish_WithDifferentEvents_ShouldSendDifferentPayloads() {
        // given
        WaitingQueuePromotedEvent first = new WaitingQueuePromotedEvent(900L, 1L, 1L, 1L, START_AT, END_AT, PROMOTED_AT);
        WaitingQueuePromotedEvent second = new WaitingQueuePromotedEvent(901L, 2L, 2L, 2L, START_AT, END_AT, PROMOTED_AT);

        // when
        waitingQueueEventPublisher.publish(first);
        waitingQueueEventPublisher.publish(second);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(2))
                .convertAndSend(eq(WaitingQueueEventChannel.WAITING_QUEUE_EVENT), payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues()).hasSize(2);
        assertThat(payloadCaptor.getAllValues().get(0)).isNotEqualTo(payloadCaptor.getAllValues().get(1));
    }
}
