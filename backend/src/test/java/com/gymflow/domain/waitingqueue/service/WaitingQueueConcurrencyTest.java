package com.gymflow.domain.waitingqueue.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.service.ReservationService;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRedisRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Testcontainers MySQL/Redis와 ExecutorService를 사용해 WaitingQueue Registration Lock의
 * 동시성 제어와, 서로 다른 사용자 간 waitingRank FIFO 순서 보장(Redis sequence + Lua 원자화)을
 * 검증한다. Mockito 기반 단위 테스트로는 여러 스레드의 실제 경합을 재현할 수 없기 때문에 별도의
 * 통합 테스트로 분리했다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WaitingQueueConcurrencyTest {

    @Autowired
    private WaitingQueueService waitingQueueService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private WaitingQueueRepository waitingQueueRepository;

    @Autowired
    private WaitingQueueRedisRepository waitingQueueRedisRepository;

    @Autowired
    private PromotionProcessor promotionProcessor;

    @Autowired
    private WaitingQueuePromotionRepository waitingQueuePromotionRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    private Long persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("WaitingQueue Concurrency Tester")
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

    private long countWaiting(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        return waitingQueueRepository.findAll().stream()
                .filter(wq -> wq.getResource().getId().equals(resourceId))
                .filter(wq -> wq.getStartAt().equals(startAt) && wq.getEndAt().equals(endAt))
                .filter(wq -> wq.getStatus() == WaitingQueueStatus.WAITING)
                .count();
    }

    @Test
    @DisplayName("동일 사용자가 동일 Resource/시간대에 10개의 등록 요청을 동시에 보내면 정확히 1건만 성공한다")
    void registerWaitingQueue_WithSameUserSameSlotConcurrentRequests_ShouldOnlyCreateOneWaitingQueue()
            throws InterruptedException {
        // given: A가 예약(14:00~14:15)을 점유하고 있어 대기열 등록이 가능한 상태다
        Long resourceId = persistResourceWithPolicy("Duplicate Concurrent Resource " + System.nanoTime());
        Long occupyingUserId = persistUser("occupying-user-" + System.nanoTime() + "@gymflow.com");
        Long waitingUserId = persistUser("waiting-user-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(occupyingUserId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        List<ErrorCode> failureCodes = new CopyOnWriteArrayList<>();

        // when: 동일 사용자가 동일 Resource/시간대에 대기열 등록을 10회 동시에 요청한다
        IntStream.range(0, threadCount).forEach(i -> executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(waitingUserId);
                waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
                successCount.incrementAndGet();
            } catch (BusinessException e) {
                failureCodes.add(e.getErrorCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
                doneLatch.countDown();
            }
        }));
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 정확히 1건만 성공하고, 나머지는 IN_PROGRESS 또는 ALREADY_EXISTS로 실패한다
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCodes).hasSize(threadCount - 1);
        assertThat(failureCodes).allMatch(code ->
                code == ErrorCode.WAITING_QUEUE_IN_PROGRESS || code == ErrorCode.WAITING_QUEUE_ALREADY_EXISTS);

        assertThat(countWaiting(resourceId, startAt, endAt)).isEqualTo(1);
        WaitingQueue waitingQueue = waitingQueueRepository.findAll().stream()
                .filter(wq -> wq.getResource().getId().equals(resourceId))
                .filter(wq -> wq.getStatus() == WaitingQueueStatus.WAITING)
                .findFirst()
                .orElseThrow();
        List<Long> members = waitingQueueRedisRepository.findAll(resourceId, startAt);
        assertThat(members).containsExactly(waitingQueue.getId());
    }

    @Test
    @DisplayName("서로 다른 사용자가 동일 Resource/시간대에 동시에 대기열 등록을 요청하면 둘 다 성공한다")
    void registerWaitingQueue_WithDifferentUsersSameSlot_ShouldBothSucceed() throws InterruptedException {
        // given: A가 예약(14:00~14:15)을 점유하고 있어 대기열 등록이 가능한 상태다
        Long resourceId = persistResourceWithPolicy("Different User Concurrent Resource " + System.nanoTime());
        Long occupyingUserId = persistUser("occupying-user-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("waiting-user-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("waiting-user-c-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(occupyingUserId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();

        // when: B와 C가 동일 Resource/시간대에 동시에 대기열 등록을 요청한다
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(userBId);
                waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
                successCount.incrementAndGet();
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
                waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
                successCount.incrementAndGet();
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

        // then: 서로 다른 사용자는 Lock Key가 달라 둘 다 성공하고, 각각 정상적으로 순번이 부여된다
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(countWaiting(resourceId, startAt, endAt)).isEqualTo(2);
        assertThat(waitingQueueRedisRepository.findAll(resourceId, startAt)).hasSize(2);
    }

    @Test
    @DisplayName("동일 사용자가 같은 Resource의 서로 다른 시간대에 동시에 대기열 등록을 요청하면 둘 다 성공한다")
    void registerWaitingQueue_WithSameUserDifferentSlots_ShouldBothSucceed() throws InterruptedException {
        // given: 두 시간대(14:00~14:15, 14:15~14:30) 모두 예약으로 점유되어 있다
        Long resourceId = persistResourceWithPolicy("Different Slot Concurrent Resource " + System.nanoTime());
        Long occupyingUserId = persistUser("occupying-user-" + System.nanoTime() + "@gymflow.com");
        Long waitingUserId = persistUser("waiting-user-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime firstStart = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime firstEnd = firstStart.plusMinutes(15);
        LocalDateTime secondStart = firstEnd;
        LocalDateTime secondEnd = secondStart.plusMinutes(15);

        authenticateAs(occupyingUserId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, firstStart, 15));
        reservationService.createReservation(new ReservationCreateRequest(resourceId, secondStart, 15));
        SecurityContextHolder.clearContext();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();

        // when: 동일 사용자가 서로 다른 시간대에 동시에 대기열 등록을 요청한다
        executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(waitingUserId);
                waitingQueueService.registerWaitingQueue(
                        new WaitingQueueCreateRequest(resourceId, firstStart, firstEnd));
                successCount.incrementAndGet();
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
                authenticateAs(waitingUserId);
                waitingQueueService.registerWaitingQueue(
                        new WaitingQueueCreateRequest(resourceId, secondStart, secondEnd));
                successCount.incrementAndGet();
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

        // then: 시간대가 다르므로 Lock Key가 달라 둘 다 성공한다
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(countWaiting(resourceId, firstStart, firstEnd)).isEqualTo(1);
        assertThat(countWaiting(resourceId, secondStart, secondEnd)).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 사용자 20명이 동일 Resource/시간대 대기열에 동시에 진입해도 " +
            "waitingRank가 1..20으로 중복/누락 없이 배정되고, Redis 순서와 승급 순서가 그 rank와 일치한다")
    void registerWaitingQueue_With20DifferentUsersSameSlot_ShouldAssignUniqueFifoRanksAndPromoteInRankOrder()
            throws InterruptedException {
        // given: A가 예약을 점유하고 있어 대기열 등록이 가능한 상태다
        Long resourceId = persistResourceWithPolicy("20-User Concurrent Resource " + System.nanoTime());
        Long occupyingUserId = persistUser("occupying-user-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(occupyingUserId);
        reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        int userCount = 20;
        List<Long> waitingUserIds = IntStream.range(0, userCount)
                .mapToObj(i -> persistUser("waiting-user-" + i + "-" + System.nanoTime() + "@gymflow.com"))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(userCount);
        CountDownLatch readyLatch = new CountDownLatch(userCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(userCount);
        List<WaitingQueueResponse> responses = new CopyOnWriteArrayList<>();

        // when: 20명의 서로 다른 사용자가 동일 Resource/시간대에 동시에 대기열 등록을 요청한다
        waitingUserIds.forEach(userId -> executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                authenticateAs(userId);
                WaitingQueueResponse response = waitingQueueService.registerWaitingQueue(
                        new WaitingQueueCreateRequest(resourceId, startAt, endAt));
                responses.add(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SecurityContextHolder.clearContext();
                doneLatch.countDown();
            }
        }));
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 20명 전원이 성공하고, waitingRank가 정확히 1..20으로 중복/누락 없이 배정된다
        assertThat(completed).isTrue();
        assertThat(responses).hasSize(userCount);
        List<Long> ranks = responses.stream().map(WaitingQueueResponse::waitingRank).toList();
        assertThat(ranks).doesNotContainNull();
        Set<Long> uniqueRanks = Set.copyOf(ranks);
        assertThat(uniqueRanks).hasSize(userCount);
        assertThat(uniqueRanks).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, userCount).boxed().toList());

        // and: Redis ZSET의 순서(findAll, score=sequence 오름차순)가 waitingRank 오름차순과 정확히 일치한다
        List<Long> waitingQueueIdsByRank = responses.stream()
                .sorted(Comparator.comparingLong(WaitingQueueResponse::waitingRank))
                .map(WaitingQueueResponse::waitingQueueId)
                .toList();
        assertThat(waitingQueueRedisRepository.findAll(resourceId, startAt)).containsExactlyElementsOf(waitingQueueIdsByRank);

        // and: 1등(waitingRank=1)이 실제로 다음 승급 대상이다. PromotionProcessor.tryPromote()는
        // Redis ZSET을 findAll()로 새로 읽어(=score/sequence 오름차순) 그중 첫 WAITING 후보를
        // 승급시키므로, 이 검증은 "join 응답에 찍힌 rank"와 "실제 승급 순서"가 일치함을 보여준다.
        Long firstInLineWaitingQueueId = responses.stream()
                .filter(r -> r.waitingRank() == 1L)
                .map(WaitingQueueResponse::waitingQueueId)
                .findFirst()
                .orElseThrow();

        promotionProcessor.tryPromote(resourceId, startAt, endAt);

        WaitingQueuePromotion promotion = waitingQueuePromotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();
        assertThat(promotion.getWaitingQueue().getId()).isEqualTo(firstInLineWaitingQueueId);

        WaitingQueue promotedWaitingQueue = waitingQueueRepository.findById(firstInLineWaitingQueueId).orElseThrow();
        assertThat(promotedWaitingQueue.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
    }
}
