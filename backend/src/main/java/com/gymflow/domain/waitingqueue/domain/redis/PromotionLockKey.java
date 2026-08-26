package com.gymflow.domain.waitingqueue.domain.redis;

import java.time.LocalDateTime;

public final class PromotionLockKey {

    private static final String KEY_PREFIX = "gymflow:lock:promotion:";

    private PromotionLockKey() {
    }

    public static String from(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        return KEY_PREFIX + resourceId + ":" + startAt + ":" + endAt;
    }
}
