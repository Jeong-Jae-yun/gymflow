package com.gymflow.domain.resource.domain.redis;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.global.metrics.LockMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@DataRedisTest
@Import(TestcontainersConfiguration.class)
class ResourceAvailabilityLockRepositoryTest {

    private static final Long RESOURCE_ID = 101L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ResourceAvailabilityLockRepository repository;
    private LockMetrics lockMetrics;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lockMetrics = new LockMetrics(meterRegistry);
        repository = new ResourceAvailabilityLockRepository(redisTemplate, lockMetrics);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("Lock 획득에 성공한다")
    void tryLock_ShouldSucceed() {
        Optional<String> token = repository.tryLock(RESOURCE_ID);

        assertThat(token).isPresent();
        assertThat(redisTemplate.hasKey(ResourceAvailabilityLockKey.from(RESOURCE_ID))).isTrue();
    }

    @Test
    @DisplayName("Lock 획득에 성공하면 gymflow_lock_acquired_total(lock_name=resource-availability)이 기록된다")
    void tryLock_OnSuccess_ShouldRecordAcquiredMetricWithResourceAvailabilityLockName() {
        repository.tryLock(RESOURCE_ID);

        assertThat(meterRegistry.get("gymflow_lock_acquired_total")
                .tag("lock_name", "resource-availability").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_failed_total")
                .tag("lock_name", "resource-availability").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds")
                .tag("lock_name", "resource-availability").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("동일 Resource에 대한 재획득을 시도하면 실패한다")
    void tryLock_WithSameResource_ShouldFailOnSecondAttempt() {
        Optional<String> first = repository.tryLock(RESOURCE_ID);
        Optional<String> second = repository.tryLock(RESOURCE_ID);

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("동일 Resource에 대한 재획득이 실패하면 gymflow_lock_failed_total(lock_name=resource-availability)이 기록된다")
    void tryLock_WhenSecondAttemptFails_ShouldRecordFailedMetricWithResourceAvailabilityLockName() {
        repository.tryLock(RESOURCE_ID);
        repository.tryLock(RESOURCE_ID);

        assertThat(meterRegistry.get("gymflow_lock_acquired_total")
                .tag("lock_name", "resource-availability").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_failed_total")
                .tag("lock_name", "resource-availability").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_lock_wait_seconds")
                .tag("lock_name", "resource-availability").timer().count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("다른 Resource의 Lock은 동시에 획득할 수 있다")
    void tryLock_WithDifferentResources_ShouldBothSucceed() {
        Optional<String> first = repository.tryLock(RESOURCE_ID);
        Optional<String> second = repository.tryLock(202L);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
    }

    @Test
    @DisplayName("정상 소유자가 unlock 하면 Key가 제거되어 재획득할 수 있다")
    void unlock_WithOwnedToken_ShouldRemoveKeyAndAllowReacquisition() {
        String token = repository.tryLock(RESOURCE_ID).orElseThrow();

        repository.unlock(RESOURCE_ID, token);

        Optional<String> reacquire = repository.tryLock(RESOURCE_ID);
        assertThat(reacquire).isPresent();
    }

    @Test
    @DisplayName("다른(forged) token으로 unlock을 시도하면 실제 Lock은 그대로 남는다")
    void unlock_WithForgedToken_ShouldNotRemoveRealLock() {
        repository.tryLock(RESOURCE_ID).orElseThrow();

        repository.unlock(RESOURCE_ID, "forged-token");

        Optional<String> reacquire = repository.tryLock(RESOURCE_ID);
        assertThat(reacquire).isEmpty();
    }

    @Test
    @DisplayName("TTL이 지나면 Lock이 자동으로 해제되어 다시 획득할 수 있다")
    void tryLock_AfterTtlExpires_ShouldSucceedAgain() throws InterruptedException {
        ResourceAvailabilityLockRepository shortTtlRepository =
                new ResourceAvailabilityLockRepository(redisTemplate, Duration.ofMillis(300), lockMetrics);
        Optional<String> first = shortTtlRepository.tryLock(RESOURCE_ID);
        assertThat(first).isPresent();

        Thread.sleep(600);
        Optional<String> second = shortTtlRepository.tryLock(RESOURCE_ID);

        assertThat(second).isPresent();
    }

    @Test
    @DisplayName("Lock 획득 중 Redis 오류가 발생하면 실패로 처리된다 (fail-closed)")
    void tryLock_WithRedisError_ShouldFailClosed() {
        StringRedisTemplate spyTemplate = spy(redisTemplate);
        ValueOperations<String, String> realValueOperations = redisTemplate.opsForValue();
        ValueOperations<String, String> spyValueOperations = spy(realValueOperations);
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(spyValueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
        when(spyTemplate.opsForValue()).thenReturn(spyValueOperations);
        ResourceAvailabilityLockRepository faultyRepository = new ResourceAvailabilityLockRepository(spyTemplate, lockMetrics);

        Optional<String> token = faultyRepository.tryLock(RESOURCE_ID);

        assertThat(token).isEmpty();
        assertThat(meterRegistry.get("gymflow_lock_failed_total")
                .tag("lock_name", "resource-availability").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("unlock 중 Redis 오류가 발생해도 예외를 전파하지 않는다")
    void unlock_WithRedisError_ShouldNotPropagateException() {
        String token = repository.tryLock(RESOURCE_ID).orElseThrow();

        StringRedisTemplate spyTemplate = spy(redisTemplate);
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(spyTemplate).execute(any(DefaultRedisScript.class), anyList(), any());
        ResourceAvailabilityLockRepository faultyRepository = new ResourceAvailabilityLockRepository(spyTemplate, lockMetrics);

        assertThatCode(() -> faultyRepository.unlock(RESOURCE_ID, token))
                .doesNotThrowAnyException();

        // unlock이 내부적으로 실패했으므로 실제 Lock Key는 그대로 남아 있다
        assertThat(redisTemplate.hasKey(ResourceAvailabilityLockKey.from(RESOURCE_ID))).isTrue();
    }
}
