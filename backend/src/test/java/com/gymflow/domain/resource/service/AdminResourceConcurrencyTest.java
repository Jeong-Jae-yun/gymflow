package com.gymflow.domain.resource.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.service.ReservationService;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.request.AdminResourceStatusUpdateRequest;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.service.PromotionService;
import com.gymflow.domain.waitingqueue.service.WaitingQueueService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자의 Resource 상태 변경(AdminResourceService.changeStatus)과, 새로운 시간 점유권을
 * 만드는 Reservation/Promotion 작업(createReservation/accept/tryPromote)이 동시에 실행되어도
 * "Resource가 MAINTENANCE/INACTIVE인 동시에 새로운 CONFIRMED Reservation(또는 활성 OFFERED
 * Promotion)이 존재"하는 write-skew 모순 상태가 만들어지지 않는지 ResourceAvailabilityLock을
 * 통해 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminResourceConcurrencyTest {

    @Autowired
    private AdminResourceService adminResourceService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private WaitingQueueService waitingQueueService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private WaitingQueuePromotionRepository promotionRepository;

    @Autowired
    private UserRepository userRepository;

    private Long persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("Admin Concurrency Tester")
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
    @DisplayName("관리자의 MAINTENANCE 전환과 신규 Reservation 생성이 동시에 발생해도 " +
            "Resource가 MAINTENANCE인 동시에 CONFIRMED Reservation이 존재하는 모순 상태는 생기지 않는다")
    void changeStatus_ConcurrentWithCreateReservation_ShouldNeverCoexistWithConfirmedReservation()
            throws InterruptedException {
        // given
        Long resourceId = persistResourceWithPolicy("Admin Race Create Resource " + System.nanoTime());
        Long userId = persistUser("admin-race-create-user-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();

        // when: 사용자는 신규 예약을 생성하고, 동시에 관리자는 같은 Resource를 MAINTENANCE로 전환한다
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(userId);
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // ResourceAvailabilityLock 경합 또는 Resource 상태 재검증에 의한 실패는 예상된 결과 중 하나다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
                doneLatch.countDown();
            }
        });
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                adminResourceService.changeStatus(
                        resourceId, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // ResourceAvailabilityLock 경합 또는 활성 점유 재검증에 의한 실패는 예상된 결과 중 하나다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: ResourceAvailabilityLock이 두 작업을 직렬화하므로 정확히 하나만 성공한다
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);

        // and: 절대 허용되지 않는 모순 상태(Resource=MAINTENANCE/INACTIVE AND CONFIRMED Reservation 존재)가 없어야 한다
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        boolean resourceBlocked =
                resource.getStatus() == ResourceStatus.MAINTENANCE || resource.getStatus() == ResourceStatus.INACTIVE;
        boolean hasConfirmedReservation = reservationRepository.existsOverlapping(
                resourceId, ReservationStatus.OCCUPYING_STATUSES, startAt, endAt);

        assertThat(resourceBlocked && hasConfirmedReservation)
                .as("Resource가 MAINTENANCE/INACTIVE인 동시에 CONFIRMED Reservation이 존재해서는 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("관리자의 MAINTENANCE 전환과 Promotion Accept가 동시에 발생해도 " +
            "Resource가 MAINTENANCE인 동시에 Accept로 생성된 CONFIRMED Reservation이 존재하는 모순 상태는 생기지 않는다")
    void changeStatus_ConcurrentWithPromotionAccept_ShouldNeverCoexistWithAcceptedReservation()
            throws InterruptedException {
        // given: A의 예약이 NO_SHOW 처리되어 B가 OFFERED로 자동 승급된 상태를 미리 만든다
        Long resourceId = persistResourceWithPolicy("Admin Race Accept Resource " + System.nanoTime());
        Long userAId = persistUser("admin-race-accept-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("admin-race-accept-user-b-" + System.nanoTime() + "@gymflow.com");
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

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();

        // when: B는 승급 기회를 수락하고, 동시에 관리자는 같은 Resource를 MAINTENANCE로 전환한다
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(userBId);
                promotionService.accept(promotion.getId());
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // ResourceAvailabilityLock은 재시도 없는 fail-fast Lock이므로, Lock 경합에서 진 쪽은
                // 상대가 이미 실패로 끝났더라도(예: 활성 OFFERED Promotion 때문에 admin이 곧바로 실패)
                // 그 사실과 무관하게 즉시 RESERVATION_IN_PROGRESS로 끝난다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
                doneLatch.countDown();
            }
        });
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                adminResourceService.changeStatus(
                        resourceId, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // 이미 커밋되어 있던 활성 OFFERED Promotion 때문에 Lock을 먼저 얻어도 occupancy 검사에서
                // 막히거나, Lock 경합 자체에서 질 수도 있다 - 두 경우 모두 예상된 결과다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 이미 커밋된 활성 OFFERED Promotion이 admin을 항상 막을 수 있고, ResourceAvailabilityLock은
        // 재시도 없는 fail-fast Lock이라 Lock 경합에서 진 accept()도 곧바로 실패할 수 있다.
        // 따라서 "정확히 1"이 아니라 "동시에 성공하는 경우가 없다(최대 1)"가 이번 정책의 올바른 불변식이다
        // (기존 PromotionConcurrencyTest의 accept_WithConcurrentOverlappingNewReservation_ShouldNeverBothSucceed와 동일한 이유).
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isLessThanOrEqualTo(1);

        // and: 절대 허용되지 않는 모순 상태(Resource=MAINTENANCE/INACTIVE AND Accept로 생성된 CONFIRMED Reservation 존재)가 없어야 한다
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        boolean resourceBlocked =
                resource.getStatus() == ResourceStatus.MAINTENANCE || resource.getStatus() == ResourceStatus.INACTIVE;
        boolean hasConfirmedReservation = reservationRepository.existsOverlapping(
                resourceId, ReservationStatus.OCCUPYING_STATUSES, startAt, endAt);

        assertThat(resourceBlocked && hasConfirmedReservation)
                .as("Resource가 MAINTENANCE/INACTIVE인 동시에 Accept로 생성된 CONFIRMED Reservation이 존재해서는 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("관리자의 MAINTENANCE 전환과 취소로 트리거되는 tryPromote(OFFERED 생성)가 동시에 발생해도 " +
            "Resource가 MAINTENANCE인 동시에 활성 OFFERED Promotion이 존재하는 모순 상태는 생기지 않는다")
    void changeStatus_ConcurrentWithTryPromote_ShouldNeverCoexistWithActiveOfferedPromotion()
            throws InterruptedException {
        // given: A가 예약하고 B가 같은 시간대에 대기열로 등록한다 (아직 빈 슬롯 트리거 전)
        Long resourceId = persistResourceWithPolicy("Admin Race TryPromote Resource " + System.nanoTime());
        Long userAId = persistUser("admin-race-trypromote-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("admin-race-trypromote-user-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // when: A가 예약을 취소(-> tryPromote가 B를 OFFERED로 승급 시도)하는 동시에,
        // 관리자는 같은 Resource를 MAINTENANCE로 전환한다
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(userAId);
                reservationService.cancelReservation(
                        createdByA.reservationId(), new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
                doneLatch.countDown();
            }
        });
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                adminResourceService.changeStatus(
                        resourceId, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE));
            } catch (BusinessException e) {
                // ResourceAvailabilityLock 경합에 의한 실패는 예상된 결과 중 하나다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        // then: Resource가 MAINTENANCE/INACTIVE인 동시에 활성 OFFERED Promotion이 존재해서는 안 된다
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        boolean resourceBlocked =
                resource.getStatus() == ResourceStatus.MAINTENANCE || resource.getStatus() == ResourceStatus.INACTIVE;
        boolean hasActiveOfferedPromotion = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .isPresent();

        assertThat(resourceBlocked && hasActiveOfferedPromotion)
                .as("Resource가 MAINTENANCE/INACTIVE인 동시에 활성 OFFERED Promotion이 존재해서는 안 된다")
                .isFalse();
    }
}
