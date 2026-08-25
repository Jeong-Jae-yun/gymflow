package com.gymflow.domain.reservation.domain.redis;

import com.gymflow.domain.reservation.service.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class ReservationNoShowListener extends KeyExpirationEventMessageListener {

    private final ReservationService reservationService;

    public ReservationNoShowListener(
            RedisMessageListenerContainer listenerContainer, ReservationService reservationService) {
        super(listenerContainer);
        this.reservationService = reservationService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (ObjectUtils.isEmpty(message.getBody())) {
            return;
        }

        String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);
        ReservationNoShowKey.toReservationId(expiredKey).ifPresent(reservationId -> {
            try {
                reservationService.handleNoShowExpiration(reservationId);
            } catch (RuntimeException e) {
                log.error("NO_SHOW 자동 처리 중 오류가 발생했습니다. reservationId={}", reservationId, e);
            }
        });
    }
}
