package com.gymflow.domain.reservation.domain.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class ReservationNoShowRepository {

    private static final String MARKER_VALUE = "PENDING";

    private final StringRedisTemplate redisTemplate;

    public void register(Long reservationId, Duration ttl) {
        String key = ReservationNoShowKey.from(reservationId);
        redisTemplate.opsForValue().set(key, MARKER_VALUE, ttl);
    }

    public boolean exists(Long reservationId) {
        String key = ReservationNoShowKey.from(reservationId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void remove(Long reservationId) {
        String key = ReservationNoShowKey.from(reservationId);
        redisTemplate.delete(key);
    }
}
