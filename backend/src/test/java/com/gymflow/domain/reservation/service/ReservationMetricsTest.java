package com.gymflow.domain.reservation.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private ReservationMetrics reservationMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reservationMetrics = new ReservationMetrics(meterRegistry);
    }

    @Test
    @DisplayName("recordCreated 호출 시 gymflow_reservation_created_total counter가 1 증가한다")
    void recordCreated_ShouldIncrementCreatedCounter() {
        reservationMetrics.recordCreated();
        reservationMetrics.recordCreated();

        assertThat(meterRegistry.get("gymflow_reservation_created_total").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordConflict 호출 시 gymflow_reservation_conflict_total counter가 1 증가한다")
    void recordConflict_ShouldIncrementConflictCounter() {
        reservationMetrics.recordConflict();

        assertThat(meterRegistry.get("gymflow_reservation_conflict_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordFailed 호출 시 gymflow_reservation_failed_total counter가 1 증가한다")
    void recordFailed_ShouldIncrementFailedCounter() {
        reservationMetrics.recordFailed();

        assertThat(meterRegistry.get("gymflow_reservation_failed_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("각 counter는 서로 독립적으로 증가하며 다른 counter에 영향을 주지 않는다")
    void countersAreIndependent() {
        reservationMetrics.recordCreated();
        reservationMetrics.recordConflict();
        reservationMetrics.recordConflict();
        reservationMetrics.recordFailed();
        reservationMetrics.recordFailed();
        reservationMetrics.recordFailed();

        assertThat(meterRegistry.get("gymflow_reservation_created_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_reservation_conflict_total").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("gymflow_reservation_failed_total").counter().count()).isEqualTo(3.0);
    }
}
