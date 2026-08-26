package com.gymflow.domain.resource.domain.redis;

public final class ResourceAvailabilityLockKey {

    private static final String KEY_PREFIX = "gymflow:lock:resource-availability:";

    private ResourceAvailabilityLockKey() {
    }

    public static String from(Long resourceId) {
        return KEY_PREFIX + resourceId;
    }
}
