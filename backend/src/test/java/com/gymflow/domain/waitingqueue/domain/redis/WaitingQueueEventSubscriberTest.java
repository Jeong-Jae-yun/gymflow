package com.gymflow.domain.waitingqueue.domain.redis;

import com.gymflow.domain.waitingqueue.domain.websocket.WaitingQueueDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WaitingQueueEventSubscriberTest {

    private static final String CHANNEL = WaitingQueueEventChannel.WAITING_QUEUE_EVENT;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private WaitingQueueEventSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new WaitingQueueEventSubscriber(new RedisMessageListenerContainer(), objectMapper, messagingTemplate);
    }

    private Message message(String body) {
        return new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }

    private WaitingQueuePromotedEvent sampleEvent() {
        return new WaitingQueuePromotedEvent(
                500L, 10L, 3L, 101L,
                LocalDateTime.of(2026, 8, 20, 14, 0),
                LocalDateTime.of(2026, 8, 20, 14, 15),
                LocalDateTime.of(2026, 8, 20, 13, 58));
    }

    @Test
    @DisplayName("올바른 JSON 메시지를 수신하면 예외 없이 이벤트로 역직렬화하고 WebSocket으로 전송한다")
    void onMessage_WithValidJson_ShouldDeserializeAndSendToUser() {
        // given
        WaitingQueuePromotedEvent event = sampleEvent();
        String json = objectMapper.writeValueAsString(event);

        // when
        subscriber.onMessage(message(json), null);

        // then
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                userCaptor.capture(), destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(userCaptor.getValue()).isEqualTo(String.valueOf(event.userId()));
        assertThat(destinationCaptor.getValue()).isEqualTo(WaitingQueueDestination.WAITING_QUEUE_QUEUE);
        assertThat(destinationCaptor.getValue()).isEqualTo("/queue/waiting-queue");
        assertThat(payloadCaptor.getValue()).isEqualTo(event);
        assertThat(((WaitingQueuePromotedEvent) payloadCaptor.getValue()).promotionId()).isEqualTo(500L);
    }

    @Test
    @DisplayName("잘못된 형식의 JSON을 수신해도 애플리케이션이 죽지 않고, WebSocket 전송도 하지 않는다")
    void onMessage_WithMalformedJson_ShouldNotThrowAndNotSend() {
        // when & then
        assertThatCode(() -> subscriber.onMessage(message("{invalid-json"), null))
                .doesNotThrowAnyException();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("무관한 형식의 JSON 메시지를 수신해도 예외 없이 무시된다")
    void onMessage_WithUnrelatedJsonShape_ShouldNotThrow() {
        // when & then
        assertThatCode(() -> subscriber.onMessage(message("{\"foo\":\"bar\"}"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("메시지 본문이 비어 있으면 아무 것도 하지 않고, WebSocket 전송도 하지 않는다")
    void onMessage_WithEmptyBody_ShouldBeIgnoredAndNotSend() {
        // given
        Message emptyMessage = new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8), new byte[0]);

        // when & then
        assertThatCode(() -> subscriber.onMessage(emptyMessage, null)).doesNotThrowAnyException();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("정상 JSON이어도 WebSocket 전송 중 예외가 발생하면 외부로 전파되지 않는다")
    void onMessage_WithMessagingFailure_ShouldNotThrow() {
        // given
        WaitingQueuePromotedEvent event = sampleEvent();
        String json = objectMapper.writeValueAsString(event);
        doThrow(new RuntimeException("WebSocket 전송 실패"))
                .when(messagingTemplate)
                .convertAndSendToUser(eq(String.valueOf(event.userId())), eq(WaitingQueueDestination.WAITING_QUEUE_QUEUE), any());

        // when & then
        assertThatCode(() -> subscriber.onMessage(message(json), null)).doesNotThrowAnyException();
    }
}
