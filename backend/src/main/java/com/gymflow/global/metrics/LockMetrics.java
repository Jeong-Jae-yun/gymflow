package com.gymflow.global.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class LockMetrics {

    private static final String LOCK_NAME_TAG = "lock_name";

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> acquiredCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> failedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> waitTimers = new ConcurrentHashMap<>();

    public LockMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void recordAcquired(String lockName, Timer.Sample sample) {
        registerLockName(lockName);
        acquiredCounter(lockName).increment();
        sample.stop(waitTimer(lockName));
    }

    public void recordFailed(String lockName, Timer.Sample sample) {
        registerLockName(lockName);
        failedCounter(lockName).increment();
        sample.stop(waitTimer(lockName));
    }

    private void registerLockName(String lockName) {
        acquiredCounter(lockName);
        failedCounter(lockName);
        waitTimer(lockName);
    }

    private Counter acquiredCounter(String lockName) {
        return acquiredCounters.computeIfAbsent(lockName, name -> Counter.builder("gymflow_lock_acquired_total")
                .description("Redis 분산락 획득에 성공한 횟수")
                .tag(LOCK_NAME_TAG, name)
                .register(meterRegistry));
    }

    private Counter failedCounter(String lockName) {
        return failedCounters.computeIfAbsent(lockName, name -> Counter.builder("gymflow_lock_failed_total")
                .description("Redis 분산락 획득에 실패한 횟수")
                .tag(LOCK_NAME_TAG, name)
                .register(meterRegistry));
    }

    private Timer waitTimer(String lockName) {
        return waitTimers.computeIfAbsent(lockName, name -> Timer.builder("gymflow_lock_wait_seconds")
                .description("Redis 분산락 1회 시도(SETNX 호출)를 시작한 시점부터 성공/실패가 결정될 때까지 걸린 시간. "
                        + "재시도 없이 즉시 성공/실패하는 tryLock 방식이므로, 대기열에서의 대기 시간이 아니라 "
                        + "락 획득 시도 자체의 지연(attempt latency)을 의미한다.")
                .tag(LOCK_NAME_TAG, name)
                .register(meterRegistry));
    }
}
