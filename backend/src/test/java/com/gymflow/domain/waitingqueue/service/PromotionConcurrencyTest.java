package com.gymflow.domain.waitingqueue.service;

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
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
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
 * 실제 Testcontainers MySQL/Redis와 ExecutorService를 사용해 Promotion(ACCEPT, OFFERED 생성)과
 * 일반 Reservation 생성 사이의 동시성 제어(ReservationSlotLock 공유)를 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PromotionConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private WaitingQueueService waitingQueueService;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private WaitingQueuePromotionRepository promotionRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    private Long persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("Promotion Concurrency Tester")
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
    @DisplayName("Promotion ACCEPT와 겹치는(동일하지 않은) 시간대의 신규 예약 생성은 동시에 실행되어도 둘 다 성공하지 않는다")
    void accept_WithConcurrentOverlappingNewReservation_ShouldNeverBothSucceed() throws InterruptedException {
        // given: A의 예약이 NO_SHOW 처리되어 B가 OFFERED로 자동 승급된다 (14:00~14:15)
        Long resourceId = persistResourceWithPolicy("Accept Overlapping Create Resource " + System.nanoTime());
        Long userAId = persistUser("accept-overlap-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("accept-overlap-user-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("accept-overlap-user-c-" + System.nanoTime() + "@gymflow.com");
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

        // when: B는 승급 기회를 수락(14:00~14:15)하고, 동시에 C는 겹치는 14:05~14:20 신규 예약을 시도한다
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(userBId);
                promotionService.accept(promotion.getId());
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // 락 경합 또는 overlap 재검증에 의한 실패는 예상된 결과 중 하나다
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
                authenticateAs(userCId);
                reservationService.createReservation(
                        new ReservationCreateRequest(resourceId, startAt.plusMinutes(5), 15));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // 락 경합 또는 overlap 재검증에 의한 실패는 예상된 결과 중 하나다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
                doneLatch.countDown();
            }
        });
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 겹치는 두 시간대(14:00~14:15, 14:05~14:20)에 대해 두 작업이 동시에 성공해서는 안 된다.
        // ReservationSlotLock은 즉시 실패(fail-fast) 방식이라 재시도 없이 락 경합에서 진 쪽은 그대로
        // RESERVATION_IN_PROGRESS로 끝난다. C가 락을 먼저 얻는 경우 Active OFFERED Promotion과의
        // overlap 재검증(RESERVATION_PROMOTION_RESERVED)으로 C도 정상적으로 차단되므로, 이 경우 두
        // 작업이 모두 실패(successCount=0)하는 것도 유효한 결과다. 따라서 "정확히 1"이 아니라
        // "동시에 성공하는 경우가 없다(최대 1)"가 이번 정책 변경 이후의 올바른 불변식이다.
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("예약 취소로 트리거되는 Promotion OFFERED 생성과 겹치는 신규 예약 생성이 동시에 발생해도 둘 다 성립하지 않는다")
    void tryPromote_WithConcurrentOverlappingNewReservation_ShouldNeverCoexistWithConflictingReservation()
            throws InterruptedException {
        // given: A가 예약(14:00~14:15)하고 B가 같은 시간대에 대기열로 등록한다
        Long resourceId = persistResourceWithPolicy("TryPromote Overlapping Create Resource " + System.nanoTime());
        Long userAId = persistUser("try-promote-overlap-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("try-promote-overlap-user-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("try-promote-overlap-user-c-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);
        LocalDateTime concurrentCreateStart = startAt.plusMinutes(5);

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
        // C는 겹치는 14:10~14:25 신규 예약을 시도한다
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
                authenticateAs(userCId);
                reservationService.createReservation(new ReservationCreateRequest(resourceId, concurrentCreateStart, 15));
            } catch (BusinessException e) {
                // 락 경합 또는 overlap 재검증에 의한 실패는 예상된 결과 중 하나다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
                doneLatch.countDown();
            }
        });
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        // then: 겹치는 시간대(14:00~14:15, 14:10~14:25)에 대해 OFFERED Promotion과
        // 점유 상태(CONFIRMED/CHECKED_IN) Reservation이 동시에 존재해서는 안 된다
        boolean offeredExists = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .isPresent();
        boolean conflictingReservationExists = reservationRepository.existsOverlapping(
                resourceId, ReservationStatus.OCCUPYING_STATUSES, concurrentCreateStart, concurrentCreateStart.plusMinutes(15));

        assertThat(offeredExists && conflictingReservationExists)
                .as("OFFERED Promotion과 겹치는 점유 Reservation이 동시에 존재해서는 안 된다")
                .isFalse();
    }
}
