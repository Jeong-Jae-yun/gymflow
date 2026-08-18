package com.gymflow.domain.reservation.domain.redis;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataRedisTest
@Import(TestcontainersConfiguration.class)
class ReservationLockRepositoryTest {

    private static final Long RESOURCE_ID = 101L;
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 20, 14, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 20, 14, 15);

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ReservationLockRepository reservationLockRepository;

    @BeforeEach
    void setUp() {
        reservationLockRepository = new ReservationLockRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("Key가 비어 있으면 Lock 획득에 성공하고 lockToken을 반환한다")
    void tryLock_WhenKeyIsAbsent_ShouldSucceed() {
        // when
        Optional<String> lockToken = reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT);

        // then
        assertThat(lockToken).isPresent();
    }

    @Test
    @DisplayName("이미 Lock이 걸려 있으면 동일 Key에 대한 두 번째 획득은 실패한다")
    void tryLock_WhenAlreadyLocked_ShouldFail() {
        // given
        reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT);

        // when
        Optional<String> secondLockToken = reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT);

        // then
        assertThat(secondLockToken).isEmpty();
    }

    @Test
    @DisplayName("서로 다른 Resource는 동시에 Lock을 획득할 수 있다")
    void tryLock_WithDifferentResources_ShouldBothSucceed() {
        // when
        Optional<String> first = reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT);
        Optional<String> second = reservationLockRepository.tryLock(202L, START_AT, END_AT);

        // then
        assertThat(first).isPresent();
        assertThat(second).isPresent();
    }

    @Test
    @DisplayName("서로 다른 startAt/endAt 구간은 동시에 Lock을 획득할 수 있다")
    void tryLock_WithDifferentTimeRange_ShouldBothSucceed() {
        // when
        Optional<String> first = reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT);
        Optional<String> second =
                reservationLockRepository.tryLock(RESOURCE_ID, END_AT, END_AT.plusMinutes(15));

        // then
        assertThat(first).isPresent();
        assertThat(second).isPresent();
    }

    @Test
    @DisplayName("TTL이 지나면 Lock이 자동으로 해제되어 다시 획득할 수 있다")
    void tryLock_AfterTtlExpires_ShouldSucceedAgain() throws InterruptedException {
        // given
        ReservationLockRepository shortTtlRepository =
                new ReservationLockRepository(redisTemplate, Duration.ofMillis(300));
        Optional<String> firstLockToken = shortTtlRepository.tryLock(RESOURCE_ID, START_AT, END_AT);
        assertThat(firstLockToken).isPresent();

        // when
        Thread.sleep(600);
        Optional<String> secondLockToken = shortTtlRepository.tryLock(RESOURCE_ID, START_AT, END_AT);

        // then
        assertThat(secondLockToken).isPresent();
    }

    @Test
    @DisplayName("Lock 소유자가 정상적으로 해제하면 이후 재획득할 수 있다")
    void unlock_WithOwnedLock_ShouldAllowReacquisition() {
        // given
        String lockToken = reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT).orElseThrow();

        // when
        reservationLockRepository.unlock(RESOURCE_ID, START_AT, END_AT, lockToken);

        // then
        Optional<String> reacquired = reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT);
        assertThat(reacquired).isPresent();
    }

    @Test
    @DisplayName("다른 소유자의 lockToken으로는 Lock을 해제할 수 없다")
    void unlock_WithDifferentOwnerToken_ShouldNotRemoveLock() {
        // given
        reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT).orElseThrow();

        // when
        reservationLockRepository.unlock(RESOURCE_ID, START_AT, END_AT, "other-owner-token");

        // then
        Optional<String> stillLocked = reservationLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT);
        assertThat(stillLocked).isEmpty();
    }
}
