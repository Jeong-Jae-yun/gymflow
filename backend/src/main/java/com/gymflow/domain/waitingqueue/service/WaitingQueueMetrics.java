package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class WaitingQueueMetrics {

    private final WaitingQueueRepository waitingQueueRepository;
    private final Counter joinedCounter;
    private final Counter cancelledCounter;
    private final Counter promotedCounter;

    public WaitingQueueMetrics(MeterRegistry meterRegistry, WaitingQueueRepository waitingQueueRepository) {
        this.waitingQueueRepository = waitingQueueRepository;
        this.joinedCounter = Counter.builder("gymflow_waiting_queue_joined_total")
                .description("대기열 등록에 성공한 횟수")
                .register(meterRegistry);
        this.cancelledCounter = Counter.builder("gymflow_waiting_queue_cancelled_total")
                .description("대기열을 정상 취소한 횟수")
                .register(meterRegistry);
        this.promotedCounter = Counter.builder("gymflow_waiting_queue_promoted_total")
                .description("WAITING 상태에서 PROMOTED 상태로 전환되며 Promotion이 생성된 횟수")
                .register(meterRegistry);
        Gauge.builder("gymflow_waiting_queue_size", this, WaitingQueueMetrics::currentWaitingSize)
                .description("현재 WAITING 상태인 대기열 인원 수")
                .register(meterRegistry);
    }

    public void recordJoined() {
        recordAfterCommit(joinedCounter::increment);
    }

    public void recordCancelled() {
        recordAfterCommit(cancelledCounter::increment);
    }

    public void recordPromoted() {
        recordAfterCommit(promotedCounter::increment);
    }

    private double currentWaitingSize() {
        return waitingQueueRepository.countByStatus(WaitingQueueStatus.WAITING);
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
