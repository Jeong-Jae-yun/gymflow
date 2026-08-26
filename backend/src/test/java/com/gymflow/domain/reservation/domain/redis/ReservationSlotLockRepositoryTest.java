package com.gymflow.domain.reservation.domain.redis;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@DataRedisTest
@Import(TestcontainersConfiguration.class)
class ReservationSlotLockRepositoryTest {

    private static final Long RESOURCE_ID = 101L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ReservationSlotLockRepository repository;

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 8, 20, hour, minute);
    }

    @BeforeEach
    void setUp() {
        repository = new ReservationSlotLockRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("단일 슬롯 구간은 Lock 획득에 성공한다")
    void tryLockAll_WithSingleSlotRange_ShouldSucceed() {
        Optional<ReservationSlotLockHandle> handle = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 5));

        assertThat(handle).isPresent();
        assertThat(handle.get().slotLocks()).hasSize(1);
    }

    @Test
    @DisplayName("다중 슬롯 구간은 모든 슬롯 Lock 획득에 성공한다")
    void tryLockAll_WithMultiSlotRange_ShouldAcquireAllSlots() {
        Optional<ReservationSlotLockHandle> handle = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 15));

        assertThat(handle).isPresent();
        assertThat(handle.get().slotLocks()).hasSize(3);
        assertThat(redisTemplate.hasKey(ReservationSlotLockKey.from(RESOURCE_ID, at(14, 0)))).isTrue();
        assertThat(redisTemplate.hasKey(ReservationSlotLockKey.from(RESOURCE_ID, at(14, 5)))).isTrue();
        assertThat(redisTemplate.hasKey(ReservationSlotLockKey.from(RESOURCE_ID, at(14, 10)))).isTrue();
    }

    @Test
    @DisplayName("겹치는 구간끼리는 공유하는 슬롯 때문에 두 번째 요청의 Lock 획득이 실패한다")
    void tryLockAll_WithOverlappingRange_ShouldFailOnSecondRequest() {
        Optional<ReservationSlotLockHandle> first = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 30));
        Optional<ReservationSlotLockHandle> second = repository.tryLockAll(RESOURCE_ID, at(14, 20), at(14, 35));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("겹치지 않는 시간대는 동시에 Lock을 획득할 수 있다")
    void tryLockAll_WithNonOverlappingRange_ShouldBothSucceed() {
        Optional<ReservationSlotLockHandle> first = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 15));
        Optional<ReservationSlotLockHandle> second = repository.tryLockAll(RESOURCE_ID, at(14, 15), at(14, 30));

        assertThat(first).isPresent();
        assertThat(second).isPresent();
    }

    @Test
    @DisplayName("서로 다른 Resource는 동일 시간대라도 동시에 Lock을 획득할 수 있다")
    void tryLockAll_WithDifferentResources_ShouldBothSucceed() {
        Optional<ReservationSlotLockHandle> first = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 15));
        Optional<ReservationSlotLockHandle> second = repository.tryLockAll(202L, at(14, 0), at(14, 15));

        assertThat(first).isPresent();
        assertThat(second).isPresent();
    }

    @Test
    @DisplayName("중간 슬롯 획득에 실패하면 이미 획득한 슬롯들은 모두 롤백된다")
    void tryLockAll_WithMidSequenceFailure_ShouldRollbackPreviouslyAcquiredSlots() {
        // given: A가 14:00~14:15({14:00,14:05,14:10})를 선점한다
        Optional<ReservationSlotLockHandle> handleA = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 15));
        assertThat(handleA).isPresent();

        // when: B가 13:50~14:05({13:50,13:55,14:00})를 시도하면 마지막 슬롯(14:00)에서 실패한다
        Optional<ReservationSlotLockHandle> handleB = repository.tryLockAll(RESOURCE_ID, at(13, 50), at(14, 5));
        assertThat(handleB).isEmpty();

        // then: B가 앞서 획득했던 13:50, 13:55는 롤백되어 다시 획득할 수 있어야 한다
        Optional<ReservationSlotLockHandle> reacquire = repository.tryLockAll(RESOURCE_ID, at(13, 50), at(14, 0));
        assertThat(reacquire).isPresent();
        assertThat(reacquire.get().slotLocks()).hasSize(2);
    }

    @Test
    @DisplayName("다른 소유자의 token으로는 개별 슬롯 Lock을 해제할 수 없다")
    void unlockAll_WithForgedToken_ShouldNotRemoveRealLock() {
        // given
        ReservationSlotLockHandle handle = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 5)).orElseThrow();
        ReservationSlotLockHandle.SlotLock realLock = handle.slotLocks().get(0);
        ReservationSlotLockHandle forgedHandle = new ReservationSlotLockHandle(
                List.of(new ReservationSlotLockHandle.SlotLock(realLock.redisKey(), "forged-token")));

        // when
        repository.unlockAll(forgedHandle);

        // then: 실제 소유자의 Lock은 그대로 남아 있어 재획득이 실패한다
        Optional<ReservationSlotLockHandle> reacquire = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 5));
        assertThat(reacquire).isEmpty();
    }

    @Test
    @DisplayName("정상 소유자가 unlockAll 하면 모든 슬롯 Key가 제거되어 재획득할 수 있다")
    void unlockAll_WithOwnedHandle_ShouldRemoveAllKeysAndAllowReacquisition() {
        // given
        ReservationSlotLockHandle handle = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 15)).orElseThrow();

        // when
        repository.unlockAll(handle);

        // then
        Optional<ReservationSlotLockHandle> reacquire = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 15));
        assertThat(reacquire).isPresent();
        assertThat(redisTemplate.hasKey(ReservationSlotLockKey.from(RESOURCE_ID, at(14, 0)))).isTrue();
    }

    @Test
    @DisplayName("TTL이 지나면 슬롯 Lock이 자동으로 해제되어 다시 획득할 수 있다")
    void tryLockAll_AfterTtlExpires_ShouldSucceedAgain() throws InterruptedException {
        // given
        ReservationSlotLockRepository shortTtlRepository = new ReservationSlotLockRepository(redisTemplate, Duration.ofMillis(300));
        Optional<ReservationSlotLockHandle> first = shortTtlRepository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 10));
        assertThat(first).isPresent();

        // when
        Thread.sleep(600);
        Optional<ReservationSlotLockHandle> second = shortTtlRepository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 10));

        // then
        assertThat(second).isPresent();
    }

    @Test
    @DisplayName("슬롯 획득 도중 Redis 오류가 발생하면 이미 획득한 실제 슬롯을 최대한 정리하고 실패로 처리한다")
    void tryLockAll_WithRedisErrorMidSequence_ShouldCleanUpPreviouslyAcquiredSlots() {
        // given: 실제 Redis에 연결된 template을 spy하여, 세 번째 슬롯(14:10)에 대한 SET만 오류를 발생시킨다.
        // 앞선 14:00/14:05는 실제로 Redis에 SET되고, unlock 시에도 실제 CAS 스크립트가 실행된다.
        StringRedisTemplate spyTemplate = spy(redisTemplate);
        ValueOperations<String, String> realValueOperations = redisTemplate.opsForValue();
        ValueOperations<String, String> spyValueOperations = spy(realValueOperations);
        String failingKey = ReservationSlotLockKey.from(RESOURCE_ID, at(14, 10));
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(spyValueOperations).setIfAbsent(eq(failingKey), anyString(), any(Duration.class));
        when(spyTemplate.opsForValue()).thenReturn(spyValueOperations);
        ReservationSlotLockRepository faultyRepository = new ReservationSlotLockRepository(spyTemplate);

        // when: 14:00~14:15는 14:00,14:05,14:10 세 슬롯 중 마지막에서 실패한다
        Optional<ReservationSlotLockHandle> handle = faultyRepository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 15));

        // then: 실패로 처리되고, 앞서 실제로 획득했던 14:00/14:05 Key는 정리되어 다시 획득할 수 있다
        assertThat(handle).isEmpty();
        assertThat(redisTemplate.hasKey(ReservationSlotLockKey.from(RESOURCE_ID, at(14, 0)))).isFalse();
        assertThat(redisTemplate.hasKey(ReservationSlotLockKey.from(RESOURCE_ID, at(14, 5)))).isFalse();
        Optional<ReservationSlotLockHandle> reacquire = repository.tryLockAll(RESOURCE_ID, at(14, 0), at(14, 10));
        assertThat(reacquire).isPresent();
    }
}
