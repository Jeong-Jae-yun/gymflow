package com.gymflow.domain.reservation.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
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
}
