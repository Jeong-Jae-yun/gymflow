package com.gymflow.domain.waitingqueue.domain.redis;

import java.time.LocalDateTime;

public final class WaitingQueueRedisKey {

    private static final String KEY_PREFIX = "gymflow:queue:";

    private WaitingQueueRedisKey() {
    }

    public static String from(Long resourceId, LocalDateTime startAt) {
        return KEY_PREFIX + resourceId + ":" + startAt;
    }

    public static String sequenceKeyFrom(Long resourceId, LocalDateTime startAt) {
        return from(resourceId, startAt) + ":sequence";
    }
}
