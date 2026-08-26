package com.gymflow.domain.waitingqueue.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
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
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRedisRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * 실제 Testcontainers MySQL/Redis + 실제 Spring 컨텍스트(@Transactional 포함)를 사용해
 * "tryPromote() 내부의 WaitingQueue Redis ZSET 조회(findAll) 실패가 이미 성공한
 * 핵심 MySQL 상태 변경을 롤백시키지 않는다"(Fail-Open)는 것을 검증한다.
 *
 * PromotionService.pollNextWaitingCandidate()가 findAll()을 try/catch 없이 호출하던
 * 결함 수정 전에는, 이 예외가 @Transactional 경계까지 전파되어 cancelReservation()/reject() 등의
 * 트랜잭션을 rollback-only로 만들거나(참여 트랜잭션) 직접 rollback시켰다(진입점 트랜잭션, self-invocation).
 * 이 테스트는 Mockito mock이 아닌 실제 Spring 빈(MockitoSpyBean)을 사용해 그 실제 트랜잭션
 * 경계에서의 동작을 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PromotionRedisFailOpenIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private WaitingQueueService waitingQueueService;

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private WaitingQueuePromotionRepository promotionRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private WaitingQueueRedisRepository waitingQueueRedisRepository;

    private Long persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("Promotion Redis Fail-Open Tester")
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
    @DisplayName("Reservation 취소 후 자동 승급 시도 중 Redis ZSET 조회가 실패해도 취소(CANCELLED)는 롤백되지 않는다")
    void cancelReservation_WithWaitingQueueRedisFindAllFailure_ShouldNotRollbackCancellation() {
        // given: A가 예약하고, B가 같은 시간대에 대기열로 등록한다
        Long resourceId = persistResourceWithPolicy("Redis FailOpen Cancel Resource " + System.nanoTime());
        Long userAId = persistUser("redis-failopen-cancel-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("redis-failopen-cancel-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        // 취소로 트리거되는 tryPromote() 내부의 대기열 조회(findAll)가 Redis 장애로 실패하도록 만든다
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(waitingQueueRedisRepository).findAll(eq(resourceId), eq(startAt));

        // when: A가 예약을 취소한다 (cancelReservation()은 @Transactional 이며, 내부에서
        // tryPromote()가 findAll 실패로 예외를 던지더라도 참여 트랜잭션이 rollback-only가 되면 안 된다)
        authenticateAs(userAId);
        ReservationResponse cancelResponse = reservationService.cancelReservation(
                createdByA.reservationId(), new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE));
        SecurityContextHolder.clearContext();

        // then: 응답과 DB 재조회 모두 CANCELLED — UnexpectedRollbackException 없이 정상 commit되었음을 의미한다
        assertThat(cancelResponse.status()).isEqualTo(ReservationStatus.CANCELLED);
        Reservation reloaded = reservationRepository.findById(createdByA.reservationId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        // and: Redis 장애로 대기열 후보를 알 수 없었으므로 B는 승급되지 않는다 (Known Limitation, 이번 검증의 핵심은 아님)
        assertThat(promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED))
                .isEmpty();
    }

    @Test
    @DisplayName("Promotion 거절 이후 재승급 시도 중 Redis ZSET 조회가 실패해도 거절(REJECTED)은 롤백되지 않는다")
    void reject_WithWaitingQueueRedisFindAllFailure_ShouldNotRollbackRejection() {
        // given: A가 예약하고 B가 대기열에 등록한 뒤, A가 NO_SHOW 처리되어 B가 OFFERED로 자동 승급된다.
        // 이 초기 승급 탐색(findAll)은 noShow() 시점에 이미 정상적으로(spy의 기본 real-call 위임으로)
        // 끝나 있으므로, 이후 stub을 걸면 reject()가 유발하는 재승급 시도의 findAll 호출부터 실패한다.
        Long resourceId = persistResourceWithPolicy("Redis FailOpen Reject Resource " + System.nanoTime());
        Long userAId = persistUser("redis-failopen-reject-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("redis-failopen-reject-b-" + System.nanoTime() + "@gymflow.com");
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

        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(waitingQueueRedisRepository).findAll(eq(resourceId), eq(startAt));

        // when: B가 승급 기회를 거절한다 (reject()는 자신의 transaction commit 이후 별도 Bean인
        // PromotionProcessor.tryPromote()를 새 transaction에서 호출하며, 이때 findAll 호출이
        // Redis 장애로 실패한다)
        authenticateAs(userBId);
        promotionService.reject(promotion.getId());
        SecurityContextHolder.clearContext();

        // then: DB 재조회 결과가 REJECTED — reject() 자신의 트랜잭션이 롤백되지 않았음을 의미한다
        WaitingQueuePromotion reloaded = promotionRepository.findById(promotion.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PromotionStatus.REJECTED);
    }
}
