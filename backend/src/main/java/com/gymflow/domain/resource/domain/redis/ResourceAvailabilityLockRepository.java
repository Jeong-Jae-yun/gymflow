package com.gymflow.domain.resource.domain.redis;

import com.gymflow.global.metrics.LockMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class ResourceAvailabilityLockRepository {

    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(5);
    private static final String LOCK_NAME = "resource-availability";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration lockTtl;
    private final LockMetrics lockMetrics;

    @Autowired
    public ResourceAvailabilityLockRepository(StringRedisTemplate redisTemplate, LockMetrics lockMetrics) {
        this(redisTemplate, DEFAULT_LOCK_TTL, lockMetrics);
    }

    ResourceAvailabilityLockRepository(StringRedisTemplate redisTemplate, Duration lockTtl, LockMetrics lockMetrics) {
        this.redisTemplate = redisTemplate;
        this.lockTtl = lockTtl;
        this.lockMetrics = lockMetrics;
    }

    public Optional<String> tryLock(Long resourceId) {
        String key = ResourceAvailabilityLockKey.from(resourceId);
        String lockToken = UUID.randomUUID().toString();
        Timer.Sample sample = lockMetrics.startTimer();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, lockToken, lockTtl);
            if (Boolean.TRUE.equals(acquired)) {
                lockMetrics.recordAcquired(LOCK_NAME, sample);
                return Optional.of(lockToken);
            }
            lockMetrics.recordFailed(LOCK_NAME, sample);
            return Optional.empty();
        } catch (RuntimeException e) {
            lockMetrics.recordFailed(LOCK_NAME, sample);
            log.error("Resource Availability Lock 획득에 실패했습니다. key={}", key, e);
            return Optional.empty();
        }
    }

    public void unlock(Long resourceId, String lockToken) {
        String key = ResourceAvailabilityLockKey.from(resourceId);
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), lockToken);
        } catch (RuntimeException e) {
            log.error("Resource Availability Lock 해제에 실패했습니다. key={}", key, e);
        }
    }
}
