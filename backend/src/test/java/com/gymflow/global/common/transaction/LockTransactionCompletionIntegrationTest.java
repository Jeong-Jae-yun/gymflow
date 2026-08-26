package com.gymflow.global.common.transaction;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.service.ReservationService;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceAvailabilityLockRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.request.AdminResourceStatusUpdateRequest;
import com.gymflow.domain.resource.service.AdminResourceService;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionLockRepository;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRegistrationLockRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.service.PromotionService;
import com.gymflow.domain.waitingqueue.service.WaitingQueueService;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Service 메서드가 반환되고 Redis Lock이 해제된 뒤에도 실제 MySQL COMMIT은 아직 발생하지 않을 수
 * 있다"는 기존 gap이 이번 TransactionAwareLockReleaser 도입으로 닫혔는지를 실제 Testcontainers
 * MySQL/Redis + 실제 Spring transaction 경계로 검증한다.
 *
 * 각 Service 메서드를 외부에서 감싼 TransactionTemplate 하나의 physical transaction 안에서 실행한다
 * (REQUIRED 전파이므로 Service 자신의 @Transactional은 이 외부 transaction에 참여만 한다). Service
 * 호출이 반환된 뒤에도 외부 TransactionTemplate이 아직 commit을 호출하지 않은 시점을 만들 수 있으므로,
 * 그 시점에 같은 Lock Key를 재획득 시도해 "아직 잠겨 있어야 한다"를 확인하고, 외부 transaction이
 * commit된 이후에는 "이제는 재획득할 수 있어야 한다"를 확인한다. Thread.sleep 없이 latch/barrier와
 * TransactionTemplate만으로 결정적으로 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LockTransactionCompletionIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private AdminResourceService adminResourceService;

    @Autowired
    private WaitingQueueService waitingQueueService;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WaitingQueuePromotionRepository promotionRepository;

    @Autowired
    private ResourceAvailabilityLockRepository resourceAvailabilityLockRepository;

    @Autowired
    private ReservationSlotLockRepository reservationSlotLockRepository;

    @Autowired
    private WaitingQueueRegistrationLockRepository waitingQueueRegistrationLockRepository;

    @Autowired
    private PromotionLockRepository promotionLockRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("Lock Transaction Boundary Tester")
                .build()).getId();
    }

    private Long persistResourceWithPolicy(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(10)
                .maxDuration(60)
                .build();
        return resourceRepository.save(resource).getId();
    }

    private void authenticateAs(Long userId) {
        CustomUserDetails principal = new CustomUserDetails(userId, "user-" + userId + "@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    @DisplayName("createReservation()의 ResourceAvailabilityLock은 실제 별도 thread가 commit 이전에는 재획득할 수 없고, commit 이후에만 재획득할 수 있다")
    void createReservation_ResourceAvailabilityLock_NotReacquirableBeforeCommit_ReacquirableAfterCommit()
            throws InterruptedException {
        Long resourceId = persistResourceWithPolicy("Commit Gap Availability Resource " + System.nanoTime());
        Long userId = persistUser("commit-gap-availability-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);

        CountDownLatch writeDoneNotYetCommitted = new CountDownLatch(1);
        CountDownLatch reacquireAttemptDone = new CountDownLatch(1);
        AtomicBoolean reacquiredDuringGap = new AtomicBoolean(true);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                writeDoneNotYetCommitted.await(5, TimeUnit.SECONDS);
                Optional<String> attempt = resourceAvailabilityLockRepository.tryLock(resourceId);
                reacquiredDuringGap.set(attempt.isPresent());
                attempt.ifPresent(token -> resourceAvailabilityLockRepository.unlock(resourceId, token));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                reacquireAttemptDone.countDown();
            }
        });

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        authenticateAs(userId);
        try {
            outerTemplate.execute(status -> {
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
                writeDoneNotYetCommitted.countDown();
                awaitUninterruptibly(reacquireAttemptDone);
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
            executor.shutdown();
        }

        assertThat(reacquiredDuringGap)
                .as("DB commit 이전에는 다른 요청이 같은 ResourceAvailabilityLock을 재획득할 수 없어야 한다")
                .isFalse();

        Optional<String> afterCommit = resourceAvailabilityLockRepository.tryLock(resourceId);
        assertThat(afterCommit)
                .as("DB commit 이후에는 ResourceAvailabilityLock을 다시 획득할 수 있어야 한다")
                .isPresent();
        afterCommit.ifPresent(token -> resourceAvailabilityLockRepository.unlock(resourceId, token));
    }

    @Test
    @DisplayName("createReservation()의 ReservationSlotLock도 commit 이전에는 재획득할 수 없고, commit 이후에만 재획득할 수 있다")
    void createReservation_ReservationSlotLock_NotReacquirableBeforeCommit_ReacquirableAfterCommit() {
        Long resourceId = persistResourceWithPolicy("Commit Gap Slot Resource " + System.nanoTime());
        Long userId = persistUser("commit-gap-slot-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(15).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        authenticateAs(userId);
        AtomicBoolean reacquiredDuringGap = new AtomicBoolean(true);
        try {
            outerTemplate.execute(status -> {
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
                // Redis Lock 상태 확인은 MySQL 커넥션/트랜잭션과 무관하게 즉시 반영되므로, 같은
                // 스레드에서 바로 재획득을 시도해도 "다른 요청의 시도"와 동일하게 검증된다.
                Optional<ReservationSlotLockHandle> attempt =
                        reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt);
                reacquiredDuringGap.set(attempt.isPresent());
                attempt.ifPresent(reservationSlotLockRepository::unlockAll);
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(reacquiredDuringGap)
                .as("DB commit 이전에는 다른 요청이 같은 ReservationSlotLock을 재획득할 수 없어야 한다")
                .isFalse();

        Optional<ReservationSlotLockHandle> afterCommit =
                reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt);
        assertThat(afterCommit)
                .as("DB commit 이후에는 ReservationSlotLock을 다시 획득할 수 있어야 한다")
                .isPresent();
        afterCommit.ifPresent(reservationSlotLockRepository::unlockAll);
    }

    @Test
    @DisplayName("extendReservation()의 ReservationSlotLock은 commit 이전에는 재획득할 수 없고, commit 이후에만 재획득할 수 있다")
    void extendReservation_ReservationSlotLock_NotReacquirableBeforeCommit_ReacquirableAfterCommit() {
        Long resourceId = persistResourceWithPolicy("Commit Gap Extend Resource " + System.nanoTime());
        Long userId = persistUser("commit-gap-extend-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(2);

        authenticateAs(userId);
        ReservationResponse created =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        ReservationResponse checkedIn = reservationService.checkInReservation(created.reservationId());
        SecurityContextHolder.clearContext();

        LocalDateTime deltaStart = checkedIn.endAt();
        LocalDateTime newEndAt = deltaStart.plusMinutes(15);

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        authenticateAs(userId);
        AtomicBoolean reacquiredDuringGap = new AtomicBoolean(true);
        try {
            outerTemplate.execute(status -> {
                reservationService.extendReservation(created.reservationId(), new ReservationExtensionRequest(15));
                Optional<ReservationSlotLockHandle> attempt =
                        reservationSlotLockRepository.tryLockAll(resourceId, deltaStart, newEndAt);
                reacquiredDuringGap.set(attempt.isPresent());
                attempt.ifPresent(reservationSlotLockRepository::unlockAll);
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(reacquiredDuringGap)
                .as("DB commit 이전에는 다른 요청이 같은 ReservationSlotLock을 재획득할 수 없어야 한다")
                .isFalse();

        Optional<ReservationSlotLockHandle> afterCommit =
                reservationSlotLockRepository.tryLockAll(resourceId, deltaStart, newEndAt);
        assertThat(afterCommit)
                .as("DB commit 이후에는 ReservationSlotLock을 다시 획득할 수 있어야 한다")
                .isPresent();
        afterCommit.ifPresent(reservationSlotLockRepository::unlockAll);
    }

    @Test
    @DisplayName("AdminResourceService.changeStatus()의 ResourceAvailabilityLock은 commit 이전에는 재획득할 수 없고, commit 이후에만 재획득할 수 있다")
    void changeStatus_ResourceAvailabilityLock_NotReacquirableBeforeCommit_ReacquirableAfterCommit() {
        Long resourceId = persistResourceWithPolicy("Commit Gap Admin Resource " + System.nanoTime());

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        AtomicBoolean reacquiredDuringGap = new AtomicBoolean(true);
        outerTemplate.execute(status -> {
            adminResourceService.changeStatus(resourceId, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE));
            Optional<String> attempt = resourceAvailabilityLockRepository.tryLock(resourceId);
            reacquiredDuringGap.set(attempt.isPresent());
            attempt.ifPresent(token -> resourceAvailabilityLockRepository.unlock(resourceId, token));
            return null;
        });

        assertThat(reacquiredDuringGap)
                .as("DB commit 이전에는 다른 요청이 같은 ResourceAvailabilityLock을 재획득할 수 없어야 한다")
                .isFalse();

        Optional<String> afterCommit = resourceAvailabilityLockRepository.tryLock(resourceId);
        assertThat(afterCommit)
                .as("DB commit 이후에는 ResourceAvailabilityLock을 다시 획득할 수 있어야 한다")
                .isPresent();
        afterCommit.ifPresent(token -> resourceAvailabilityLockRepository.unlock(resourceId, token));
    }

    @Test
    @DisplayName("registerWaitingQueue()의 WaitingQueueRegistrationLock은 commit 이전에는 재획득할 수 없고, commit 이후에만 재획득할 수 있다")
    void registerWaitingQueue_RegistrationLock_NotReacquirableBeforeCommit_ReacquirableAfterCommit() {
        Long resourceId = persistResourceWithPolicy("Commit Gap WaitingQueue Resource " + System.nanoTime());
        Long ownerUserId = persistUser("commit-gap-wq-owner-" + System.nanoTime() + "@gymflow.com");
        Long waitingUserId = persistUser("commit-gap-wq-waiting-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(16).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(ownerUserId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        authenticateAs(waitingUserId);
        AtomicBoolean reacquiredDuringGap = new AtomicBoolean(true);
        try {
            outerTemplate.execute(status -> {
                waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
                Optional<String> attempt = waitingQueueRegistrationLockRepository
                        .tryLock(waitingUserId, resourceId, startAt, endAt);
                reacquiredDuringGap.set(attempt.isPresent());
                attempt.ifPresent(token ->
                        waitingQueueRegistrationLockRepository.unlock(waitingUserId, resourceId, startAt, endAt, token));
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(reacquiredDuringGap)
                .as("DB commit 이전에는 다른 요청이 같은 WaitingQueueRegistrationLock을 재획득할 수 없어야 한다")
                .isFalse();

        Optional<String> afterCommit =
                waitingQueueRegistrationLockRepository.tryLock(waitingUserId, resourceId, startAt, endAt);
        assertThat(afterCommit)
                .as("DB commit 이후에는 WaitingQueueRegistrationLock을 다시 획득할 수 있어야 한다")
                .isPresent();
        afterCommit.ifPresent(token ->
                waitingQueueRegistrationLockRepository.unlock(waitingUserId, resourceId, startAt, endAt, token));
    }

    @Test
    @DisplayName("accept()의 ResourceAvailabilityLock/PromotionLock/ReservationSlotLock 모두 commit 이전에는 재획득할 수 없고, commit 이후에만 재획득할 수 있다")
    void accept_AllThreeLocks_NotReacquirableBeforeCommit_ReacquirableAfterCommit() {
        Long resourceId = persistResourceWithPolicy("Commit Gap Accept Resource " + System.nanoTime());
        Long userAId = persistUser("commit-gap-accept-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("commit-gap-accept-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(1).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        reservationService.noShow(createdByA.reservationId());
        WaitingQueuePromotion promotion = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        authenticateAs(userBId);
        AtomicBoolean availabilityReacquired = new AtomicBoolean(true);
        AtomicBoolean promotionReacquired = new AtomicBoolean(true);
        AtomicBoolean slotReacquired = new AtomicBoolean(true);
        try {
            outerTemplate.execute(status -> {
                promotionService.accept(promotion.getId());

                Optional<String> availabilityAttempt = resourceAvailabilityLockRepository.tryLock(resourceId);
                availabilityReacquired.set(availabilityAttempt.isPresent());
                availabilityAttempt.ifPresent(token -> resourceAvailabilityLockRepository.unlock(resourceId, token));

                Optional<String> promotionAttempt = promotionLockRepository.tryLock(resourceId, startAt, endAt);
                promotionReacquired.set(promotionAttempt.isPresent());
                promotionAttempt.ifPresent(token -> promotionLockRepository.unlock(resourceId, startAt, endAt, token));

                Optional<ReservationSlotLockHandle> slotAttempt =
                        reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt);
                slotReacquired.set(slotAttempt.isPresent());
                slotAttempt.ifPresent(reservationSlotLockRepository::unlockAll);
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(availabilityReacquired).as("commit 이전에는 ResourceAvailabilityLock을 재획득할 수 없어야 한다").isFalse();
        assertThat(promotionReacquired).as("commit 이전에는 PromotionLock을 재획득할 수 없어야 한다").isFalse();
        assertThat(slotReacquired).as("commit 이전에는 ReservationSlotLock을 재획득할 수 없어야 한다").isFalse();

        Optional<String> availabilityAfterCommit = resourceAvailabilityLockRepository.tryLock(resourceId);
        assertThat(availabilityAfterCommit).as("commit 이후에는 ResourceAvailabilityLock을 재획득할 수 있어야 한다").isPresent();
        availabilityAfterCommit.ifPresent(token -> resourceAvailabilityLockRepository.unlock(resourceId, token));

        Optional<String> promotionAfterCommit = promotionLockRepository.tryLock(resourceId, startAt, endAt);
        assertThat(promotionAfterCommit).as("commit 이후에는 PromotionLock을 재획득할 수 있어야 한다").isPresent();
        promotionAfterCommit.ifPresent(token -> promotionLockRepository.unlock(resourceId, startAt, endAt, token));

        Optional<ReservationSlotLockHandle> slotAfterCommit =
                reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt);
        assertThat(slotAfterCommit).as("commit 이후에는 ReservationSlotLock을 재획득할 수 있어야 한다").isPresent();
        slotAfterCommit.ifPresent(reservationSlotLockRepository::unlockAll);
    }

    @Test
    @DisplayName("createReservation()을 감싼 transaction이 rollback되면, Reservation은 저장되지 않고 두 Lock 모두 afterCompletion에서 해제된다")
    void createReservation_WithOuterRollback_ReleasesBothLocksAndDoesNotPersist() {
        Long resourceId = persistResourceWithPolicy("Commit Gap Rollback Resource " + System.nanoTime());
        Long userId = persistUser("commit-gap-rollback-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(17).withMinute(0).withSecond(0).withNano(0);

        TransactionTemplate outerTemplate = new TransactionTemplate(transactionManager);
        authenticateAs(userId);
        try {
            outerTemplate.execute(status -> {
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
                status.setRollbackOnly();
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }

        boolean anyReservationPersisted = reservationRepository.existsOverlapping(
                resourceId, ReservationStatus.OCCUPYING_STATUSES, startAt, startAt.plusMinutes(15));
        assertThat(anyReservationPersisted).as("rollback되었으므로 Reservation은 저장되지 않아야 한다").isFalse();

        Optional<String> availabilityAfterRollback = resourceAvailabilityLockRepository.tryLock(resourceId);
        assertThat(availabilityAfterRollback).as("rollback 이후에도 ResourceAvailabilityLock은 해제되어야 한다").isPresent();
        availabilityAfterRollback.ifPresent(token -> resourceAvailabilityLockRepository.unlock(resourceId, token));

        Optional<ReservationSlotLockHandle> slotAfterRollback =
                reservationSlotLockRepository.tryLockAll(resourceId, startAt, startAt.plusMinutes(15));
        assertThat(slotAfterRollback).as("rollback 이후에도 ReservationSlotLock은 해제되어야 한다").isPresent();
        slotAfterRollback.ifPresent(reservationSlotLockRepository::unlockAll);
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
