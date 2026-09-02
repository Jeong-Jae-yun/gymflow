package com.gymflow.domain.waitingqueue.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WaitingQueueMetrics의 join/cancel/promoted counter 기록이 실제 Spring transaction의
 * commit 시점(afterCommit)에 맞춰 지연되며, rollback 시에는 기록되지 않는지 검증한다.
 *
 * 클래스 레벨 @Transactional(NOT_SUPPORTED)로 @DataJpaTest 기본 롤백 래핑을 비활성화하고,
 * TransactionTemplate으로 각 테스트에서 실제 commit/rollback을 직접 발생시킨다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WaitingQueueMetricsTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WaitingQueueRepository waitingQueueRepository;

    private SimpleMeterRegistry meterRegistry;
    private WaitingQueueMetrics waitingQueueMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        waitingQueueMetrics = new WaitingQueueMetrics(meterRegistry, waitingQueueRepository);
    }

    @Test
    @DisplayName("transaction이 활성 상태면 recordJoined()는 즉시 증가시키지 않고, commit 이후에 증가한다")
    void recordJoined_WithActiveTransaction_IncrementsOnlyAfterCommit() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            waitingQueueMetrics.recordJoined();
            assertThat(meterRegistry.get("gymflow_waiting_queue_joined_total").counter().count()).isEqualTo(0.0);
            return null;
        });

        assertThat(meterRegistry.get("gymflow_waiting_queue_joined_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("transaction이 rollback되면 recordJoined()로 기록한 join counter는 증가하지 않는다")
    void recordJoined_WithRolledBackTransaction_ShouldNotIncrement() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            waitingQueueMetrics.recordJoined();
            status.setRollbackOnly();
            return null;
        });

        assertThat(meterRegistry.get("gymflow_waiting_queue_joined_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("transaction이 rollback되면 recordCancelled()로 기록한 cancel counter는 증가하지 않는다")
    void recordCancelled_WithRolledBackTransaction_ShouldNotIncrement() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            waitingQueueMetrics.recordCancelled();
            status.setRollbackOnly();
            return null;
        });

        assertThat(meterRegistry.get("gymflow_waiting_queue_cancelled_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("transaction이 rollback되면 recordPromoted()로 기록한 promoted counter는 증가하지 않는다")
    void recordPromoted_WithRolledBackTransaction_ShouldNotIncrement() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            waitingQueueMetrics.recordPromoted();
            status.setRollbackOnly();
            return null;
        });

        assertThat(meterRegistry.get("gymflow_waiting_queue_promoted_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordJoined()는 즉시 join counter를 증가시킨다")
    void recordJoined_WithoutActiveTransaction_ShouldIncrementImmediately() {
        waitingQueueMetrics.recordJoined();

        assertThat(meterRegistry.get("gymflow_waiting_queue_joined_total").counter().count()).isEqualTo(1.0);
    }
}
