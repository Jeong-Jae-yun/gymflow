package com.gymflow.global.common.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class TransactionAwareLockReleaser {

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
