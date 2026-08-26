package com.gymflow.global.common.transaction;

import com.gymflow.TestcontainersConfiguration;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TransactionAwareLockReleaser가 실제 Spring transaction(afterCommit/afterRollback이 아니라
 * afterCompletion)에 맞춰 unlockAction 실행 시점을 결정하는지 검증한다.
 *
 * 클래스 레벨 @Transactional(NOT_SUPPORTED)로 @DataJpaTest 기본 롤백 래핑을 비활성화하고,
 * TransactionTemplate으로 각 테스트에서 실제 commit/rollback을 직접 발생시킨다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionAwareLockReleaserTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionAwareLockReleaser releaser;

    @BeforeEach
    void setUp() {
        releaser = new TransactionAwareLockReleaser();
    }

    @Test
    @DisplayName("transaction이 활성 상태면 register()는 즉시 unlock하지 않고 true를 반환하며, commit 이후에 unlock이 실행된다")
    void register_WithActiveTransaction_DefersUnlockUntilAfterCommit() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        AtomicBoolean unlocked = new AtomicBoolean(false);
        AtomicBoolean deferredResult = new AtomicBoolean(false);

        template.execute(status -> {
            boolean deferred = releaser.register(() -> unlocked.set(true));
            deferredResult.set(deferred);
            assertThat(unlocked).isFalse();
            return null;
        });

        assertThat(deferredResult).isTrue();
        assertThat(unlocked).isTrue();
    }

    @Test
    @DisplayName("transaction이 rollback되어도 afterCompletion에서 unlock이 실행된다")
    void register_WithRolledBackTransaction_StillRunsUnlockAfterCompletion() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        AtomicBoolean unlocked = new AtomicBoolean(false);

        template.execute(status -> {
            releaser.register(() -> unlocked.set(true));
            status.setRollbackOnly();
            assertThat(unlocked).isFalse();
            return null;
        });

        assertThat(unlocked).isTrue();
    }

    @Test
    @DisplayName("활성 transaction이 없으면 register()는 false를 반환하고 unlockAction을 실행하지 않는다")
    void register_WithoutTransaction_ReturnsFalseAndDoesNotRunUnlock() {
        AtomicBoolean unlocked = new AtomicBoolean(false);

        boolean deferred = releaser.register(() -> unlocked.set(true));

        assertThat(deferred).isFalse();
        assertThat(unlocked).isFalse();
    }

    @Test
    @DisplayName("synchronization은 active이지만 실제 transaction은 없는 상태라면 register()는 false를 반환한다")
    void register_WithSynchronizationActiveButNoActualTransaction_ReturnsFalse() {
        AtomicBoolean unlocked = new AtomicBoolean(false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            boolean deferred = releaser.register(() -> unlocked.set(true));

            assertThat(deferred).isFalse();
            assertThat(unlocked).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("여러 unlockAction을 같은 transaction에 등록해도 각각 afterCompletion에서 실행된다")
    void register_WithMultipleUnlockActionsInSameTransaction_RunsAllAfterCompletion() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        AtomicReference<String> order = new AtomicReference<>("");

        template.execute(status -> {
            releaser.register(() -> order.updateAndGet(s -> s + "first;"));
            releaser.register(() -> order.updateAndGet(s -> s + "second;"));
            return null;
        });

        assertThat(order.get()).isEqualTo("first;second;");
    }
}
