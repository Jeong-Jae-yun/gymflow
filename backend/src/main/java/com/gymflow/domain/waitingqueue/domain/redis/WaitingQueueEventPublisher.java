package com.gymflow.domain.waitingqueue.domain.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingQueueEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(WaitingQueuePromotedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNow(event);
                }
            });
            return;
        }
        publishNow(event);
    }

    private void publishNow(WaitingQueuePromotedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(WaitingQueueEventChannel.WAITING_QUEUE_EVENT, payload);
        } catch (RuntimeException e) {
            log.warn("WaitingQueue 승급 이벤트 발행에 실패했습니다. waitingQueueId={}", event.waitingQueueId(), e);
        }
    }
}
