package com.gymflow.domain.waitingqueue.domain.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class PromotionLockRepository {

    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(3);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration lockTtl;

    @Autowired
    public PromotionLockRepository(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_LOCK_TTL);
    }

    PromotionLockRepository(StringRedisTemplate redisTemplate, Duration lockTtl) {
        this.redisTemplate = redisTemplate;
        this.lockTtl = lockTtl;
    }

    public Optional<String> tryLock(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        String key = PromotionLockKey.from(resourceId, startAt, endAt);
        String lockToken = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, lockToken, lockTtl);
            return Boolean.TRUE.equals(acquired) ? Optional.of(lockToken) : Optional.empty();
        } catch (RuntimeException e) {
            log.error("Promotion Lock 획득에 실패했습니다. key={}", key, e);
            return Optional.empty();
        }
    }

    public void unlock(Long resourceId, LocalDateTime startAt, LocalDateTime endAt, String lockToken) {
        String key = PromotionLockKey.from(resourceId, startAt, endAt);
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), lockToken);
        } catch (RuntimeException e) {
            log.error("Promotion Lock 해제에 실패했습니다. key={}", key, e);
        }
    }
}
