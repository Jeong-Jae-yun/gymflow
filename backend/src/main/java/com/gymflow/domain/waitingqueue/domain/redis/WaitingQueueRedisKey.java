package com.gymflow.domain.waitingqueue.domain.redis;

import java.time.LocalDateTime;

public final class WaitingQueueRedisKey {

    private static final String KEY_PREFIX = "gymflow:queue:";

    private WaitingQueueRedisKey() {
    }

    public static String from(Long resourceId, LocalDateTime startAt) {
        return KEY_PREFIX + resourceId + ":" + startAt;
    }

    // FIFO 순서 결정에 쓰이는 단조 증가 시퀀스 카운터 키. add(ZADD)의 score로 이 값을 사용하면
    // createdAt(밀리초) 동률로 인한 rank 충돌이 원천적으로 발생하지 않는다.
    public static String sequenceKeyFrom(Long resourceId, LocalDateTime startAt) {
        return from(resourceId, startAt) + ":sequence";
    }
}
