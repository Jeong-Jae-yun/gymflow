package com.gymflow.domain.waitingqueue.service;

import com.gymflow.TestcontainersConfiguration;
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
 * PromotionMetrics의 accepted/rejected/expired counter 기록이 실제 Spring transaction의
 * commit 시점(afterCommit)에 맞춰 지연되며, rollback 시에는 기록되지 않는지 검증한다.
 *
 * WaitingQueueMetricsTransactionTest와 동일한 방식으로 @DataJpaTest 기본 롤백 래핑을
 * 비활성화하고 TransactionTemplate으로 실제 commit/rollback을 직접 발생시킨다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PromotionMetricsTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SimpleMeterRegistry meterRegistry;
    private PromotionMetrics promotionMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        promotionMetrics = new PromotionMetrics(meterRegistry);
    }

    @Test
    @DisplayName("transaction이 활성 상태면 recordAccepted()는 즉시 증가시키지 않고, commit 이후에 증가한다")
    void recordAccepted_WithActiveTransaction_IncrementsOnlyAfterCommit() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            promotionMetrics.recordAccepted();
            assertThat(meterRegistry.get("gymflow_promotion_accepted_total").counter().count()).isEqualTo(0.0);
            return null;
        });

        assertThat(meterRegistry.get("gymflow_promotion_accepted_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Promotion.accept()로 상태만 바뀌고 이후 transaction이 rollback되면 accepted counter는 증가하지 않는다")
    void recordAccepted_WithRolledBackTransaction_ShouldNotIncrement() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            // 실제 accept() 흐름에서는 current.accept(now) 이후 Reservation 저장이
            // 커밋 시점에 flush되며 실패할 수 있다 - 이 경우에도 rollback되면 기록되지 않아야 한다
            promotionMetrics.recordAccepted();
            status.setRollbackOnly();
            return null;
        });

        assertThat(meterRegistry.get("gymflow_promotion_accepted_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("transaction이 rollback되면 recordRejected()로 기록한 rejected counter는 증가하지 않는다")
    void recordRejected_WithRolledBackTransaction_ShouldNotIncrement() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            promotionMetrics.recordRejected();
            status.setRollbackOnly();
            return null;
        });

        assertThat(meterRegistry.get("gymflow_promotion_rejected_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("transaction이 rollback되면 recordExpired()로 기록한 expired counter는 증가하지 않는다")
    void recordExpired_WithRolledBackTransaction_ShouldNotIncrement() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.execute(status -> {
            promotionMetrics.recordExpired();
            status.setRollbackOnly();
            return null;
        });

        assertThat(meterRegistry.get("gymflow_promotion_expired_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordAccepted()는 즉시 accepted counter를 증가시킨다")
    void recordAccepted_WithoutActiveTransaction_ShouldIncrementImmediately() {
        promotionMetrics.recordAccepted();

        assertThat(meterRegistry.get("gymflow_promotion_accepted_total").counter().count()).isEqualTo(1.0);
    }
}
