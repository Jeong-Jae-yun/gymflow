package com.gymflow.domain.waitingqueue.domain.redis;

import java.util.Optional;

public final class PromotionOfferedKey {

    private static final String KEY_PREFIX = "gymflow:promotion:offered:";

    private PromotionOfferedKey() {
    }

    public static String from(Long promotionId) {
        return KEY_PREFIX + promotionId;
    }

    public static Optional<Long> toPromotionId(String key) {
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
