package com.gymflow.global.common.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 정합성 보호용 Redis Lock의 해제 시점을, Service 메서드 종료가 아니라 실제 Spring
 * transaction COMMIT/ROLLBACK 완료(afterCompletion) 시점으로 미루기 위한 helper.
 *
 * Lock 획득/해제 방법(Key, TTL, token, Lua CAS 등)은 각 도메인의 LockRepository가
 * 그대로 담당하며, 이 클래스는 "언제 unlockAction을 실행할지"만 결정한다.
 */
@Slf4j
@Component
public class TransactionAwareLockReleaser {

    /**
     * 실제 transaction이 활성 상태라면 unlockAction을 afterCompletion(COMMIT/ROLLBACK 무관)
     * 시점에 실행하도록 등록하고 true를 반환한다.
     *
     * transaction이 없거나(동기화 비활성) 등록 자체가 실패하면 아무것도 실행하지 않고
     * false를 반환한다 - 호출부는 이 경우 기존 try/finally 방식으로 즉시 unlock해야 한다.
     */
    public boolean register(Runnable unlockAction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        unlockAction.run();
                    } catch (RuntimeException e) {
                        log.warn("Transaction completion 이후 Lock 해제 중 오류가 발생했습니다. status={}", status, e);
                    }
                }
            });
            return true;
        } catch (RuntimeException e) {
            log.warn("Transaction synchronization 등록에 실패해 즉시 Lock 해제로 대체합니다.", e);
            return false;
        }
    }
}
