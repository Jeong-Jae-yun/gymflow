package com.gymflow.domain.waitingqueue.domain.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class PromotionOfferedRepository {

    private static final String MARKER_VALUE = "OFFERED";

    private final StringRedisTemplate redisTemplate;

    public void register(Long promotionId, Duration ttl) {
        String key = PromotionOfferedKey.from(promotionId);
        redisTemplate.opsForValue().set(key, MARKER_VALUE, ttl);
    }

    public boolean exists(Long promotionId) {
        String key = PromotionOfferedKey.from(promotionId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void remove(Long promotionId) {
        String key = PromotionOfferedKey.from(promotionId);
        redisTemplate.delete(key);
    }
}
