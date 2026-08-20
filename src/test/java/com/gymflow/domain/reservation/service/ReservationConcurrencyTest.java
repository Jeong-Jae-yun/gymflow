package com.gymflow.domain.reservation.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Testcontainers MySQL/Redis와 ExecutorService를 사용해 Reservation Distributed Lock의
 * 동시성 제어를 검증한다. Mockito 기반 단위 테스트로는 여러 스레드의 실제 경합을 재현할 수
 * 없기 때문에 별도의 통합 테스트로 분리했다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    private Long persistUser(String email) {
        User user = User.builder()
                .email(email)
                .password("securePassword123")
                .name("Concurrency Tester")
                .build();
        return userRepository.save(user).getId();
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
                .minDuration(15)
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
    @DisplayName("같은 Resource, 같은 시간대에 대한 동시 예약 요청은 하나만 성공한다")
    void createReservation_WithConcurrentRequestsForSameSlot_ShouldSucceedOnlyOnce() throws InterruptedException {
        // given
        int threadCount = 8;
        Long resourceId = persistResourceWithPolicy("Chest Press A-1 " + System.nanoTime());
        List<Long> userIds = IntStream.range(0, threadCount)
                .mapToObj(i -> persistUser("same-slot-user-" + i + "-" + System.nanoTime() + "@gymflow.com"))
                .toList();
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        // when
        for (Long userId : userIds) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    authenticateAs(userId);
                    reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("서로 다른 Resource에 대한 동시 예약 요청은 서로 영향을 주지 않고 모두 성공한다")
    void createReservation_WithConcurrentRequestsForDifferentResources_ShouldAllSucceed() throws InterruptedException {
        // given
        int threadCount = 5;
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(15).withMinute(0).withSecond(0).withNano(0);
        List<Long> resourceIds = IntStream.range(0, threadCount)
                .mapToObj(i -> persistResourceWithPolicy("Resource-" + i + "-" + System.nanoTime()))
                .toList();
        List<Long> userIds = IntStream.range(0, threadCount)
                .mapToObj(i -> persistUser("diff-resource-user-" + i + "-" + System.nanoTime() + "@gymflow.com"))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        // when
        for (int i = 0; i < threadCount; i++) {
            Long resourceId = resourceIds.get(i);
            Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    authenticateAs(userId);
                    reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("경계가 맞닿는 14:00~14:15와 14:15~14:30은 각각 정상적으로 예약된다")
    void createReservation_WithAdjacentTimeSlots_ShouldBothSucceed() {
        // given
        Long resourceId = persistResourceWithPolicy("Adjacent Slot Resource " + System.nanoTime());
        Long userAId = persistUser("adjacent-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("adjacent-user-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime firstStartAt =
                LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime secondStartAt = firstStartAt.plusMinutes(15);

        // when
        authenticateAs(userAId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, firstStartAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, secondStartAt, 15));
        SecurityContextHolder.clearContext();

        // then: 두 예약 모두 CONFIRMED로 실제 저장되었다 (겹치는 예약이었다면 두 번째 호출에서 예외가 발생했을 것이다)
        assertThat(reservationRepository.existsOverlapping(
                resourceId, ReservationStatus.CONFIRMED, firstStartAt, firstStartAt.plusMinutes(15)))
                .isTrue();
        assertThat(reservationRepository.existsOverlapping(
                resourceId, ReservationStatus.CONFIRMED, secondStartAt, secondStartAt.plusMinutes(15)))
                .isTrue();
    }

    @Test
    @DisplayName("동일한 CHECKED_IN 예약에 대한 동시 연장 요청은 하나만 성공한다")
    void extendReservation_WithConcurrentRequestsForSameReservation_ShouldSucceedOnlyOnce() throws InterruptedException {
        // given
        int threadCount = 8;
        Long resourceId = persistResourceWithPolicy("Extend Same Reservation " + System.nanoTime());
        Long userId = persistUser("extend-same-user-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);

        authenticateAs(userId);
        ReservationResponse created =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        reservationService.checkInReservation(created.reservationId());
        SecurityContextHolder.clearContext();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        // when: 8개의 스레드가 동일한 CHECKED_IN 예약을 동시에 14:00~14:30으로 연장 시도한다
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    authenticateAs(userId);
                    reservationService.extendReservation(
                            created.reservationId(), new ReservationExtensionRequest(15));
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failureCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: Lock이 새로운 시간 구간(14:00~14:30) 기준으로 동일하게 생성되므로 단 하나만 성공한다
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);

        Reservation reservation = reservationRepository.findById(created.reservationId()).orElseThrow();
        assertThat(reservation.getEndAt()).isEqualTo(startAt.plusMinutes(30));
        assertThat(reservation.getExtensionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("연장된 예약과 경계가 정확히 맞닿는 새 예약은 정상적으로 예약된다")
    void extendReservation_WithAdjacentNewReservation_ShouldBothSucceed() {
        // given
        Long resourceId = persistResourceWithPolicy("Extend Adjacent Resource " + System.nanoTime());
        Long userAId = persistUser("extend-adjacent-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("extend-adjacent-user-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);

        // when: 기존 예약(14:00~14:15)을 체크인 후 14:00~14:30으로 연장한다
        authenticateAs(userAId);
        ReservationResponse created =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        reservationService.checkInReservation(created.reservationId());
        ReservationResponse extended = reservationService.extendReservation(
                created.reservationId(), new ReservationExtensionRequest(15));
        SecurityContextHolder.clearContext();

        // 연장된 예약의 새 endAt(14:30)과 정확히 맞닿는 14:30~14:45 예약을 등록한다
        authenticateAs(userBId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, extended.endAt(), 15));
        SecurityContextHolder.clearContext();

        // then: 경계가 맞닿는 두 예약 모두 정상적으로 저장되었다
        assertThat(extended.endAt()).isEqualTo(startAt.plusMinutes(30));
        assertThat(reservationRepository.existsOverlapping(
                resourceId, ReservationStatus.CONFIRMED, extended.endAt(), extended.endAt().plusMinutes(15)))
                .isTrue();
    }

    @Test
    @DisplayName("CHECKED_IN 예약이 연장되는 동안 겹치는 시간대의 신규 예약 요청은 최종적으로 둘 다 성공하지 않는다")
    void extendReservation_WithConcurrentOverlappingNewReservation_ShouldNotBothSucceed() throws InterruptedException {
        // given: 기존 예약(14:00~14:15)을 체크인해 CHECKED_IN 상태로 만든다
        Long resourceId = persistResourceWithPolicy("Extend Overlapping New Reservation " + System.nanoTime());
        Long userAId = persistUser("extend-overlap-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("extend-overlap-user-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);

        authenticateAs(userAId);
        ReservationResponse created =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        reservationService.checkInReservation(created.reservationId());
        SecurityContextHolder.clearContext();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();

        // when: A는 14:00~14:30으로 연장을 시도하고, 동시에 B는 겹치는 14:20~14:35 신규 예약을 시도한다
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(userAId);
                reservationService.extendReservation(created.reservationId(), new ReservationExtensionRequest(15));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // 점유 상태(CONFIRMED/CHECKED_IN) 기준 overlap 재검증에 의한 실패는 예상된 결과 중 하나다
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
                authenticateAs(userBId);
                reservationService.createReservation(
                        new ReservationCreateRequest(resourceId, startAt.plusMinutes(20), 15));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                // 점유 상태(CONFIRMED/CHECKED_IN) 기준 overlap 재검증에 의한 실패는 예상된 결과 중 하나다
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

        // then: 연장된 A(14:00~14:30)와 신규 B(14:20~14:35)는 시간이 겹치므로 최종적으로 하나만 성공해야 한다
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
    }
}
