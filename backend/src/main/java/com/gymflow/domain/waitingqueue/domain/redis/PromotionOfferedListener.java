package com.gymflow.domain.waitingqueue.domain.redis;

import com.gymflow.domain.waitingqueue.service.PromotionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class PromotionOfferedListener extends KeyExpirationEventMessageListener {

    private final PromotionService promotionService;

    public PromotionOfferedListener(
            RedisMessageListenerContainer listenerContainer, PromotionService promotionService) {
        super(listenerContainer);
        this.promotionService = promotionService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (ObjectUtils.isEmpty(message.getBody())) {
            return;
        }

        String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);
        PromotionOfferedKey.toPromotionId(expiredKey).ifPresent(promotionId -> {
            try {
                promotionService.handleOfferedExpiration(promotionId);
            } catch (RuntimeException e) {
                log.error("Promotion OFFERED 만료 자동 처리 중 오류가 발생했습니다. promotionId={}", promotionId, e);
            }
        });
    }
}
