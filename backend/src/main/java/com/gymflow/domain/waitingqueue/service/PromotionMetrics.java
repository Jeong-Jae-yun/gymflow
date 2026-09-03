package com.gymflow.domain.waitingqueue.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PromotionMetrics {

    private final Counter acceptedCounter;
    private final Counter rejectedCounter;
    private final Counter expiredCounter;

    public PromotionMetrics(MeterRegistry meterRegistry) {
        this.acceptedCounter = Counter.builder("gymflow_promotion_accepted_total")
                .description("OFFERED Promotion이 ACCEPTED로 정상 전이되고 accept 처리가 완료된 횟수")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("gymflow_promotion_rejected_total")
                .description("OFFERED Promotion이 REJECTED로 정상 전이된 횟수")
                .register(meterRegistry);
        this.expiredCounter = Counter.builder("gymflow_promotion_expired_total")
                .description("OFFERED Promotion이 EXPIRED로 정상 전이된 횟수")
                .register(meterRegistry);
    }

    public void recordAccepted() {
        recordAfterCommit(acceptedCounter::increment);
    }

    public void recordRejected() {
        recordAfterCommit(rejectedCounter::increment);
    }

    public void recordExpired() {
        recordAfterCommit(expiredCounter::increment);
    }

    private void recordAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
