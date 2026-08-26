package com.gymflow.domain.waitingqueue.domain.redis;

import com.gymflow.domain.waitingqueue.service.PromotionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PromotionCheckInListenerTest {

    private static final Long RESERVATION_ID = 9001L;
    private static final String EXPIRED_CHANNEL = "__keyevent@0__:expired";

    @Mock
    private PromotionService promotionService;

    private PromotionCheckInListener listener;

    private Message expiredMessage(String expiredKey) {
        return new DefaultMessage(
                EXPIRED_CHANNEL.getBytes(StandardCharsets.UTF_8), expiredKey.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Check-In TTL 만료 이벤트를 수신하면 reservationId를 파싱해 handleCheckInTimeout을 호출한다")
    void onMessage_WithCheckInExpiredKey_ShouldDelegateToPromotionService() {
        // given
        listener = new PromotionCheckInListener(new RedisMessageListenerContainer(), promotionService);

        // when
        listener.onMessage(expiredMessage(PromotionCheckInKey.from(RESERVATION_ID)), null);

        // then
        verify(promotionService).handleCheckInTimeout(RESERVATION_ID);
    }

    @Test
    @DisplayName("Promotion Check-In Key 형식이 아닌 만료 이벤트는 무시하고 Service를 호출하지 않는다")
    void onMessage_WithUnrelatedExpiredKey_ShouldBeIgnored() {
        // given
        listener = new PromotionCheckInListener(new RedisMessageListenerContainer(), promotionService);

        // when
        listener.onMessage(expiredMessage("gymflow:promotion:offered:501"), null);

        // then
        verify(promotionService, never()).handleCheckInTimeout(any());
    }

    @Test
    @DisplayName("메시지 본문이 비어 있으면 아무 것도 하지 않는다")
    void onMessage_WithEmptyBody_ShouldBeIgnored() {
        // given
        listener = new PromotionCheckInListener(new RedisMessageListenerContainer(), promotionService);
        Message emptyMessage = new DefaultMessage(EXPIRED_CHANNEL.getBytes(StandardCharsets.UTF_8), new byte[0]);

        // when
        listener.onMessage(emptyMessage, null);

        // then
        verify(promotionService, never()).handleCheckInTimeout(any());
    }

    @Test
    @DisplayName("handleCheckInTimeout에서 예외가 발생해도 Listener는 예외를 전파하지 않는다")
    void onMessage_WithServiceException_ShouldNotPropagate() {
        // given
        listener = new PromotionCheckInListener(new RedisMessageListenerContainer(), promotionService);
        doThrow(new RuntimeException("DB 오류")).when(promotionService).handleCheckInTimeout(RESERVATION_ID);

        // when & then
        assertThatCode(() -> listener.onMessage(expiredMessage(PromotionCheckInKey.from(RESERVATION_ID)), null))
                .doesNotThrowAnyException();
    }
}
