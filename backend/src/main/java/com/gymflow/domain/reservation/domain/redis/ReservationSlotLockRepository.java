package com.gymflow.domain.reservation.domain.redis;

import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle.SlotLock;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class ReservationSlotLockRepository {

    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(5);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration lockTtl;
    private final ReservationLockMetrics lockMetrics;

    @Autowired
    public ReservationSlotLockRepository(StringRedisTemplate redisTemplate, ReservationLockMetrics lockMetrics) {
        this(redisTemplate, DEFAULT_LOCK_TTL, lockMetrics);
    }

    ReservationSlotLockRepository(StringRedisTemplate redisTemplate, Duration lockTtl, ReservationLockMetrics lockMetrics) {
        this.redisTemplate = redisTemplate;
        this.lockTtl = lockTtl;
        this.lockMetrics = lockMetrics;
    }

    public Optional<ReservationSlotLockHandle> tryLockAll(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        List<LocalDateTime> slots = ReservationSlotLockKey.canonicalSlots(startAt, endAt);
        List<SlotLock> acquired = new ArrayList<>();
        for (LocalDateTime slot : slots) {
            Optional<SlotLock> lock = tryLockSlot(resourceId, slot);
            if (lock.isEmpty()) {
                releaseAll(acquired);
                return Optional.empty();
            }
            acquired.add(lock.get());
        }
        return Optional.of(new ReservationSlotLockHandle(acquired));
    }

    public void unlockAll(ReservationSlotLockHandle handle) {
        releaseAll(handle.slotLocks());
    }

    private Optional<SlotLock> tryLockSlot(Long resourceId, LocalDateTime slot) {
        String key = ReservationSlotLockKey.from(resourceId, slot);
        String lockToken = UUID.randomUUID().toString();
        Timer.Sample sample = lockMetrics.startTimer();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, lockToken, lockTtl);
            if (Boolean.TRUE.equals(acquired)) {
                lockMetrics.recordAcquired(sample);
                return Optional.of(new SlotLock(key, lockToken));
            }
            lockMetrics.recordFailed(sample);
            return Optional.empty();
        } catch (RuntimeException e) {
            lockMetrics.recordFailed(sample);
            log.error("Reservation Slot Lock 획득에 실패했습니다. key={}", key, e);
            return Optional.empty();
        }
    }

    private void releaseAll(List<SlotLock> slotLocks) {
        for (int i = slotLocks.size() - 1; i >= 0; i--) {
            SlotLock slotLock = slotLocks.get(i);
            try {
                redisTemplate.execute(UNLOCK_SCRIPT, List.of(slotLock.redisKey()), slotLock.token());
            } catch (RuntimeException e) {
                log.error("Reservation Slot Lock 해제에 실패했습니다. key={}", slotLock.redisKey(), e);
            }
        }
    }
}
