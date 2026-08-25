package com.gymflow.domain.resource.domain.redis;

public final class ResourceCacheKey {

    private static final String KEY_PREFIX = "gymflow:resource:";

    private ResourceCacheKey() {
    }

    public static String from(Long resourceId) {
        return KEY_PREFIX + resourceId;
    }
}
