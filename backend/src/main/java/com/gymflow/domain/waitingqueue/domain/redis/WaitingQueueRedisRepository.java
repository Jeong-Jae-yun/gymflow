package com.gymflow.domain.waitingqueue.domain.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class WaitingQueueRedisRepository {

    private static final DefaultRedisScript<Long> ADD_AND_RANK_SCRIPT = new DefaultRedisScript<>(
            "local sequence = redis.call('INCR', KEYS[1]) "
                    + "redis.call('ZADD', KEYS[2], sequence, ARGV[1]) "
                    + "return redis.call('ZRANK', KEYS[2], ARGV[1])",
            Long.class);

    private static final DefaultRedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREM', KEYS[1], ARGV[1]) "
                    + "if redis.call('ZCARD', KEYS[1]) == 0 then redis.call('DEL', KEYS[2]) end "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public Optional<Long> addAndGetRank(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String sequenceKey = WaitingQueueRedisKey.sequenceKeyFrom(resourceId, startAt);
        String queueKey = WaitingQueueRedisKey.from(resourceId, startAt);
        Long rank = redisTemplate.execute(
                ADD_AND_RANK_SCRIPT, List.of(sequenceKey, queueKey), String.valueOf(waitingQueueId));
        return Optional.ofNullable(rank);
    }

    public Optional<Long> rank(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String key = WaitingQueueRedisKey.from(resourceId, startAt);
        Long rank = redisTemplate.opsForZSet().rank(key, String.valueOf(waitingQueueId));
        return Optional.ofNullable(rank);
    }

    public void remove(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String queueKey = WaitingQueueRedisKey.from(resourceId, startAt);
        String sequenceKey = WaitingQueueRedisKey.sequenceKeyFrom(resourceId, startAt);
        redisTemplate.execute(REMOVE_SCRIPT, List.of(queueKey, sequenceKey), String.valueOf(waitingQueueId));
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
