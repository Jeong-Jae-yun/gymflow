package com.gymflow.domain.waitingqueue.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
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
import com.gymflow.domain.waitingqueue.domain.redis.PromotionLockRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.PromotionAcceptResponse;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * reject()/handleOfferedExpiration()/handleCheckInTimeout()에서 self-invocation을 제거하고
 * PromotionProcessor(별도 Bean, 새 transaction)로 후속 승급을 위임한 구조가, 실제 Testcontainers
 * MySQL/Redis + 실제 Spring transaction 경계에서 의도한 순서
 * (상태 변경 commit -> PromotionLock 해제 -> 새 transaction에서 후속 승급)로 동작하는지 검증한다.
 *
 * self-contention(같은 Key의 PromotionLock을 자기 자신이 막는 문제)이 남아있다면, 후속
 * PromotionProcessor.tryPromote()는 Lock 획득에 실패해 조용히 skip되고 다음 대기자가 OFFERED로
 * 승급되지 않는다. 따라서 "다음 대기자가 실제로 OFFERED가 되었는지" 자체가 self-contention이
 * 제거되었다는 것을 증명하는 가장 직접적인 신호다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PromotionFollowUpTransactionOrderingIntegrationTest {

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

    @Autowired
    private PromotionLockRepository promotionLockRepository;

    private Long persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("Promotion FollowUp Ordering Tester")
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
    @DisplayName("A가 거절하면 REJECTED가 commit되고, 그 뒤 같은 PromotionLock Key로 다음 대기자 C가 OFFERED로 승급된다 (self-contention 없음)")
    void reject_CommitsRejectionThenPromotesNextCandidateWithoutSelfContention() {
        // given: A가 예약(14:00~14:15)하고 B, C가 순서대로 같은 시간대에 대기열로 등록한 뒤,
        // A의 NO_SHOW로 1순위 B가 OFFERED된다. B가 자신의 기회를 거절하면 2순위 C가 다음 후보가 된다
        // (B는 이미 승급되며 대기열에서 제거되었으므로, B를 거절해도 B 자신은 다시 후보가 될 수 없다).
        Long resourceId = persistResourceWithPolicy("FollowUp Reject Resource " + System.nanoTime());
        Long userAId = persistUser("followup-reject-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("followup-reject-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("followup-reject-c-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(1).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        authenticateAs(userCId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        reservationService.noShow(createdByA.reservationId());
        WaitingQueuePromotion firstOffer = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();
        assertThat(firstOffer.getUser().getId()).isEqualTo(userBId);

        // PromotionLock이 실제로 자유 상태에서 시작하는지 확인(사전 조건)
        assertThat(promotionLockRepository.tryLock(resourceId, startAt, endAt))
                .as("승급 이전에는 PromotionLock이 잠겨 있지 않아야 한다")
                .isPresent()
                .hasValueSatisfying(token -> promotionLockRepository.unlock(resourceId, startAt, endAt, token));

        // when: B가 승급 기회를 거절한다
        authenticateAs(userBId);
        promotionService.reject(firstOffer.getId());
        SecurityContextHolder.clearContext();

        // then: B의 첫 승급은 REJECTED로 commit되었다
        WaitingQueuePromotion reloadedFirstOffer = promotionRepository.findById(firstOffer.getId()).orElseThrow();
        assertThat(reloadedFirstOffer.getStatus()).isEqualTo(PromotionStatus.REJECTED);

        // and: self-contention 없이 새 OFFERED(2순위 C)가 같은 Key로 생성되었다.
        // self-contention이 남아있었다면 PromotionProcessor.tryPromote()가 Lock 획득에 실패해
        // 이 새 OFFERED는 생성되지 않았을 것이다.
        WaitingQueuePromotion secondOffer = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow(() -> new AssertionError(
                        "reject() 이후 같은 Key로 새 OFFERED Promotion이 생성되지 않았다 (self-contention 회귀 의심)"));
        assertThat(secondOffer.getId()).isNotEqualTo(firstOffer.getId());
        assertThat(secondOffer.getUser().getId()).isEqualTo(userCId);

        // and: 후속 승급까지 끝난 뒤에는 PromotionLock이 다시 자유 상태다
        Optional<String> afterAll = promotionLockRepository.tryLock(resourceId, startAt, endAt);
        assertThat(afterAll).isPresent();
        afterAll.ifPresent(token -> promotionLockRepository.unlock(resourceId, startAt, endAt, token));
    }

    @Test
    @DisplayName("OFFERED TTL 만료 처리도 EXPIRED가 commit된 뒤 같은 Key로 다음 대기자가 OFFERED로 승급된다 (self-contention 없음)")
    void handleOfferedExpiration_CommitsExpirationThenPromotesNextCandidateWithoutSelfContention() {
        // given: B, C 순서로 대기열에 등록해, B의 OFFERED가 만료되면 2순위 C가 다음 후보가 되게 한다
        Long resourceId = persistResourceWithPolicy("FollowUp Expiration Resource " + System.nanoTime());
        Long userAId = persistUser("followup-expire-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("followup-expire-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("followup-expire-c-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(1).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        authenticateAs(userCId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        reservationService.noShow(createdByA.reservationId());
        WaitingQueuePromotion firstOffer = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();
        assertThat(firstOffer.getUser().getId()).isEqualTo(userBId);

        // when: OFFERED TTL이 만료되었다고 가정하고 직접 처리(자동 재시도 트리거 재현)
        promotionService.handleOfferedExpiration(firstOffer.getId());

        // then: 첫 OFFERED는 EXPIRED로 commit되었다
        WaitingQueuePromotion reloadedFirstOffer = promotionRepository.findById(firstOffer.getId()).orElseThrow();
        assertThat(reloadedFirstOffer.getStatus()).isEqualTo(PromotionStatus.EXPIRED);

        // and: self-contention 없이 같은 Key로 새 OFFERED(2순위 C)가 생성되었다
        WaitingQueuePromotion secondOffer = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow(() -> new AssertionError(
                        "handleOfferedExpiration() 이후 같은 Key로 새 OFFERED Promotion이 생성되지 않았다 (self-contention 회귀 의심)"));
        assertThat(secondOffer.getId()).isNotEqualTo(firstOffer.getId());
        assertThat(secondOffer.getUser().getId()).isEqualTo(userCId);
    }

    @Test
    @DisplayName("체크인 Timeout 처리도 CANCELLED가 commit된 뒤 같은 Key로 다음 대기자가 OFFERED로 승급된다 (self-contention 없음)")
    void handleCheckInTimeout_CommitsCancellationThenPromotesNextCandidateWithoutSelfContention() {
        // given: A가 승급을 수락(ACCEPTED, Reservation CONFIRMED)하고, B가 같은 시간대에 대기열로 등록한다
        Long resourceId = persistResourceWithPolicy("FollowUp Timeout Resource " + System.nanoTime());
        Long userAId = persistUser("followup-timeout-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("followup-timeout-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("followup-timeout-c-" + System.nanoTime() + "@gymflow.com");
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
        WaitingQueuePromotion offerToB = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();

        authenticateAs(userBId);
        PromotionAcceptResponse acceptResponse = promotionService.accept(offerToB.getId());
        SecurityContextHolder.clearContext();

        // C가 같은 시간대에 대기열로 등록해, B의 체크인 Timeout 발생 시 다음 후보가 된다
        authenticateAs(userCId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        // when: B가 체크인하지 않아 Timeout이 발생한다
        promotionService.handleCheckInTimeout(acceptResponse.reservationId());

        // then: B의 Reservation은 CANCELLED(PROMOTION_CHECKIN_TIMEOUT)로 commit되었다
        Reservation reloadedReservation = reservationRepository.findById(acceptResponse.reservationId()).orElseThrow();
        assertThat(reloadedReservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        // and: self-contention 없이 같은 Key로 C에게 새 OFFERED가 생성되었다
        WaitingQueuePromotion offerToC = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow(() -> new AssertionError(
                        "handleCheckInTimeout() 이후 같은 Key로 새 OFFERED Promotion이 생성되지 않았다 (self-contention 회귀 의심)"));
        assertThat(offerToC.getUser().getId()).isEqualTo(userCId);
    }

    @Test
    @DisplayName("reject()가 상태 검증에 실패해 예외를 던지면 후속 승급은 실행되지 않고 대기자는 승급되지 않는다")
    void reject_WhenTransactionalStepThrows_NeverTriggersFollowUpPromotion() {
        // given: A가 예약하고 B, C가 순서대로 대기열에 등록한 뒤 NO_SHOW로 B가 OFFERED된다. B가 그
        // 기회를 한 번 거절해(REJECTED, terminal) C가 다음 OFFERED가 된 뒤, 이미 terminal이 된
        // 첫 번째 promotionId로 다시 reject를 시도한다.
        Long resourceId = persistResourceWithPolicy("FollowUp Reject Rollback Resource " + System.nanoTime());
        Long userAId = persistUser("followup-reject-rollback-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("followup-reject-rollback-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("followup-reject-rollback-c-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(1).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        authenticateAs(userCId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        reservationService.noShow(createdByA.reservationId());
        WaitingQueuePromotion offer = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();

        authenticateAs(userBId);
        promotionService.reject(offer.getId());
        WaitingQueuePromotion secondOffer = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();
        assertThat(secondOffer.getUser().getId()).isEqualTo(userCId);

        // when: 이미 REJECTED(terminal)가 된 첫 번째 promotionId로 다시 거절을 시도한다
        assertThatThrownBy(() -> promotionService.reject(offer.getId()))
                .isInstanceOf(BusinessException.class);
        SecurityContextHolder.clearContext();

        // then: 실패한 시도는 어떤 상태도 바꾸지 않았고, 이미 진행 중이던 두 번째 OFFERED도 그대로다
        // (실패한 reject()가 엉뚱하게 후속 승급을 트리거해 새로운 OFFERED를 만들지 않았다)
        WaitingQueuePromotion stillSecondOffer = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();
        assertThat(stillSecondOffer.getId()).isEqualTo(secondOffer.getId());
    }
}
