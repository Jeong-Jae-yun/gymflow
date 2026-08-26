package com.gymflow.domain.waitingqueue.domain.redis;

import java.util.Optional;

public final class PromotionCheckInKey {

    private static final String KEY_PREFIX = "gymflow:promotion:checkin:";

    private PromotionCheckInKey() {
    }

    public static String from(Long reservationId) {
        return KEY_PREFIX + reservationId;
    }

    public static Optional<Long> toReservationId(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(key.substring(KEY_PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
