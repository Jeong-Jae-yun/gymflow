package com.gymflow.domain.reservation.domain.redis;

import java.time.LocalDateTime;

public final class ReservationLockKey {

    private static final String KEY_PREFIX = "gymflow:lock:reservation:";

    private ReservationLockKey() {
    }

    public static String from(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        return KEY_PREFIX + resourceId + ":" + startAt + ":" + endAt;
    }
}
