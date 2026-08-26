package com.gymflow.domain.waitingqueue.domain.redis;

import java.time.LocalDateTime;

public final class WaitingQueueRegistrationLockKey {

    private static final String KEY_PREFIX = "gymflow:lock:waiting-queue:";

    private WaitingQueueRegistrationLockKey() {
    }

    public static String from(Long userId, Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        return KEY_PREFIX + userId + ":" + resourceId + ":" + startAt + ":" + endAt;
    }
}
