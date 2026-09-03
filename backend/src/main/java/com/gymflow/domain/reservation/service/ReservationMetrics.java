package com.gymflow.domain.reservation.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final Counter createdCounter;
    private final Counter conflictCounter;
    private final Counter failedCounter;

    public ReservationMetrics(MeterRegistry meterRegistry) {
        this.createdCounter = Counter.builder("gymflow_reservation_created_total")
                .description("예약 생성에 성공한 횟수")
                .register(meterRegistry);
        this.conflictCounter = Counter.builder("gymflow_reservation_conflict_total")
                .description("동일 리소스/시간 충돌로 거절된 예약 생성 요청 횟수")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("gymflow_reservation_failed_total")
                .description("충돌이 아닌 사유로 실패한 예약 생성 요청 횟수")
                .register(meterRegistry);
    }

    public void recordCreated() {
        createdCounter.increment();
    }

    public void recordConflict() {
        conflictCounter.increment();
    }

    public void recordFailed() {
        failedCounter.increment();
    }
}
