package com.gymflow.domain.reservation.domain.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class ReservationLockMetrics {

    private static final String LOCK_NAME_TAG = "lock_name";
    private static final String RESERVATION_SLOT_LOCK = "reservation-slot";

    private final Counter acquiredCounter;
    private final Counter failedCounter;
    private final Timer waitTimer;

    public ReservationLockMetrics(MeterRegistry meterRegistry) {
        this.acquiredCounter = Counter.builder("gymflow_lock_acquired_total")
                .description("Redis 분산락 획득에 성공한 횟수")
                .tag(LOCK_NAME_TAG, RESERVATION_SLOT_LOCK)
                .register(meterRegistry);
        this.failedCounter = Counter.builder("gymflow_lock_failed_total")
                .description("Redis 분산락 획득에 실패한 횟수")
                .tag(LOCK_NAME_TAG, RESERVATION_SLOT_LOCK)
                .register(meterRegistry);
        this.waitTimer = Timer.builder("gymflow_lock_wait_seconds")
                .description("Redis 분산락 획득을 시도한 시점부터 성공/실패가 결정될 때까지 걸린 시간")
                .tag(LOCK_NAME_TAG, RESERVATION_SLOT_LOCK)
                .register(meterRegistry);
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void recordAcquired(Timer.Sample sample) {
        acquiredCounter.increment();
        sample.stop(waitTimer);
    }

    public void recordFailed(Timer.Sample sample) {
        failedCounter.increment();
        sample.stop(waitTimer);
    }
}
