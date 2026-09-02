package com.gymflow.domain.reservation.domain.redis;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationLockMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private ReservationLockMetrics lockMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lockMetrics = new ReservationLockMetrics(meterRegistry);
    }

    @Test
    @DisplayName("recordAcquired 호출 시 gymflow_lock_acquired_total counter가 증가하고 gymflow_lock_wait_seconds에 소요 시간이 기록된다")
    void recordAcquired_ShouldIncrementAcquiredCounterAndRecordWaitTime() {
        Timer.Sample sample = lockMetrics.startTimer();
        lockMetrics.recordAcquired(sample);

        assertThat(meterRegistry.get("gymflow_lock_acquired_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_failed_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("recordFailed 호출 시 gymflow_lock_failed_total counter가 증가하고 gymflow_lock_wait_seconds에 소요 시간이 기록된다")
    void recordFailed_ShouldIncrementFailedCounterAndRecordWaitTime() {
        Timer.Sample sample = lockMetrics.startTimer();
        lockMetrics.recordFailed(sample);

        assertThat(meterRegistry.get("gymflow_lock_failed_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_acquired_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("acquired와 failed는 서로 독립적으로 집계되며, wait timer는 두 경우 모두 누적된다")
    void acquiredAndFailedCounters_ShouldBeIndependentAndBothRecordWaitTime() {
        lockMetrics.recordAcquired(lockMetrics.startTimer());
        lockMetrics.recordAcquired(lockMetrics.startTimer());
        lockMetrics.recordFailed(lockMetrics.startTimer());

        assertThat(meterRegistry.get("gymflow_lock_acquired_total").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("gymflow_lock_failed_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds").timer().count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("lock_name 태그가 reservation-slot으로 고정되어 낮은 카디널리티를 유지한다")
    void metrics_ShouldBeTaggedWithLowCardinalityLockName() {
        lockMetrics.recordAcquired(lockMetrics.startTimer());

        assertThat(meterRegistry.get("gymflow_lock_acquired_total").tag("lock_name", "reservation-slot").counter())
                .isNotNull();
    }
}
