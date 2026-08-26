package com.gymflow.domain.waitingqueue.domain.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;


@Repository
@RequiredArgsConstructor
public class WaitingQueueRedisRepository {

    private final StringRedisTemplate redisTemplate;

    public void add(Long waitingQueueId, Long resourceId, LocalDateTime startAt, LocalDateTime createdAt) {
        String key = WaitingQueueRedisKey.from(resourceId, startAt);
        double score = createdAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        redisTemplate.opsForZSet().add(key, String.valueOf(waitingQueueId), score);
    }

    public Optional<Long> rank(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String key = WaitingQueueRedisKey.from(resourceId, startAt);
        Long rank = redisTemplate.opsForZSet().rank(key, String.valueOf(waitingQueueId));
        return Optional.ofNullable(rank);
    }

    public void remove(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String key = WaitingQueueRedisKey.from(resourceId, startAt);
        redisTemplate.opsForZSet().remove(key, String.valueOf(waitingQueueId));
    }

    public List<Long> findAll(Long resourceId, LocalDateTime startAt) {
        String key = WaitingQueueRedisKey.from(resourceId, startAt);
        Set<String> members = redisTemplate.opsForZSet().range(key, 0, -1);
        if (members == null) {
            return List.of();
        }
        return members.stream().map(Long::valueOf).toList();
    }
}
