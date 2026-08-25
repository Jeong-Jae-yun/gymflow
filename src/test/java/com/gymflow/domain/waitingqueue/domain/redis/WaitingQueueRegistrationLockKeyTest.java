package com.gymflow.domain.waitingqueue.domain.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingQueueRegistrationLockKeyTest {

    private static final Long USER_ID = 10L;
    private static final Long RESOURCE_ID = 101L;
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 24, 14, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 24, 14, 15);

    @Test
    @DisplayName("동일한 user/resource/시간대는 항상 동일한 Key를 생성한다")
    void from_WithSameArguments_ShouldProduceSameKey() {
        String key1 = WaitingQueueRegistrationLockKey.from(USER_ID, RESOURCE_ID, START_AT, END_AT);
        String key2 = WaitingQueueRegistrationLockKey.from(USER_ID, RESOURCE_ID, START_AT, END_AT);

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    @DisplayName("같은 사용자라도 시간대가 다르면 다른 Key를 생성한다")
    void from_WithSameUserDifferentTime_ShouldProduceDifferentKeys() {
        String key1 = WaitingQueueRegistrationLockKey.from(USER_ID, RESOURCE_ID, START_AT, END_AT);
        String key2 = WaitingQueueRegistrationLockKey.from(
                USER_ID, RESOURCE_ID, START_AT.plusMinutes(15), END_AT.plusMinutes(15));

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("같은 Resource/시간대라도 사용자가 다르면 다른 Key를 생성한다")
    void from_WithDifferentUsers_ShouldProduceDifferentKeys() {
        String key1 = WaitingQueueRegistrationLockKey.from(USER_ID, RESOURCE_ID, START_AT, END_AT);
        String key2 = WaitingQueueRegistrationLockKey.from(20L, RESOURCE_ID, START_AT, END_AT);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("같은 사용자라도 Resource가 다르면 다른 Key를 생성한다")
    void from_WithDifferentResources_ShouldProduceDifferentKeys() {
        String key1 = WaitingQueueRegistrationLockKey.from(USER_ID, RESOURCE_ID, START_AT, END_AT);
        String key2 = WaitingQueueRegistrationLockKey.from(USER_ID, 202L, START_AT, END_AT);

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("Key는 정해진 prefix로 시작한다")
    void from_ShouldStartWithExpectedPrefix() {
        String key = WaitingQueueRegistrationLockKey.from(USER_ID, RESOURCE_ID, START_AT, END_AT);

        assertThat(key).startsWith("gymflow:lock:waiting-queue:");
    }
}
