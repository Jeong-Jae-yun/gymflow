package com.gymflow.global.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private LockMetrics lockMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lockMetrics = new LockMetrics(meterRegistry);
    }

    @Test
    @DisplayName("recordAcquired 호출 시 gymflow_lock_acquired_total counter가 증가하고 gymflow_lock_wait_seconds에 소요 시간이 기록된다")
    void recordAcquired_ShouldIncrementAcquiredCounterAndRecordWaitTime() {
        Timer.Sample sample = lockMetrics.startTimer();
        lockMetrics.recordAcquired("reservation-slot", sample);

        assertThat(meterRegistry.get("gymflow_lock_acquired_total").tag("lock_name", "reservation-slot").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds").tag("lock_name", "reservation-slot").timer().count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("recordFailed 호출 시 gymflow_lock_failed_total counter가 증가하고 gymflow_lock_wait_seconds에 소요 시간이 기록된다")
    void recordFailed_ShouldIncrementFailedCounterAndRecordWaitTime() {
        Timer.Sample sample = lockMetrics.startTimer();
        lockMetrics.recordFailed("reservation-slot", sample);

        assertThat(meterRegistry.get("gymflow_lock_failed_total").tag("lock_name", "reservation-slot").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds").tag("lock_name", "reservation-slot").timer().count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("acquired와 failed는 서로 독립적으로 집계되며, wait timer는 두 경우 모두 누적된다")
    void acquiredAndFailedCounters_ShouldBeIndependentAndBothRecordWaitTime() {
        lockMetrics.recordAcquired("reservation-slot", lockMetrics.startTimer());
        lockMetrics.recordAcquired("reservation-slot", lockMetrics.startTimer());
        lockMetrics.recordFailed("reservation-slot", lockMetrics.startTimer());

        assertThat(meterRegistry.get("gymflow_lock_acquired_total").tag("lock_name", "reservation-slot").counter().count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.get("gymflow_lock_failed_total").tag("lock_name", "reservation-slot").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds").tag("lock_name", "reservation-slot").timer().count())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("서로 다른 lock_name은 독립된 시계열로 집계되어 서로 영향을 주지 않는다")
    void differentLockNames_ShouldBeCountedIndependently() {
        lockMetrics.recordAcquired("resource-availability", lockMetrics.startTimer());
        lockMetrics.recordFailed("resource-availability", lockMetrics.startTimer());
        lockMetrics.recordFailed("resource-availability", lockMetrics.startTimer());
        lockMetrics.recordAcquired("reservation-slot", lockMetrics.startTimer());

        assertThat(meterRegistry.get("gymflow_lock_acquired_total").tag("lock_name", "resource-availability").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_failed_total").tag("lock_name", "resource-availability").counter().count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.get("gymflow_lock_acquired_total").tag("lock_name", "reservation-slot").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_failed_total").tag("lock_name", "reservation-slot").counter().count())
                .isEqualTo(0.0);
    }
}
