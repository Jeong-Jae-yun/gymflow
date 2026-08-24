package com.gymflow.domain.waitingqueue.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
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
import com.gymflow.domain.waitingqueue.dto.response.PromotionAcceptResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 Testcontainers MySQL/Redis + 실제 Spring 컨텍스트를 사용해
 * "예약 NO_SHOW -> WaitingQueue 자동 승급 -> Promotion OFFERED -> Accept -> Reservation 생성"
 * 까지의 전체 흐름(Scenario 1)이 실제로 동작하는지 검증한다.
 *
 * Redis TTL이 실제로 만료되기를 기다리는 대신 noShow(reservationId)를 직접 호출해
 * "빈 슬롯 발생" 트리거만 재현하고, 그 이후의 자동 승급/수락 흐름은 실제 배선을 그대로 탄다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PromotionIntegrationTest {

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
                .name("Promotion E2E Tester")
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
    @DisplayName("예약이 NO_SHOW 처리되면 대기 1순위가 자동으로 OFFERED 승급되고, 수락하면 새 Reservation이 CONFIRMED로 생성된다")
    void noShow_ShouldAutoPromoteWaitingUserAndAcceptCreatesReservation() {
        // given: A가 리소스를 예약하고, B가 같은 시간대에 대기열로 등록한다
        // NO_SHOW는 now >= startAt에서만 가능하므로(FR-007), startAt을 이미 지난 시각으로 둔다.
        // 동시에 tryPromote가 남은 이용시간(endAt-now)이 minDuration(10분)보다 커야 승급하므로
        // startAt을 너무 과거로 두지 않는다.
        Long resourceId = persistResourceWithPolicy("Promotion E2E Resource " + System.nanoTime());
        Long userAId = persistUser("promotion-e2e-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("promotion-e2e-user-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(1).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        // when: A의 예약이 NO_SHOW 처리된다 (빈 슬롯 발생 트리거)
        reservationService.noShow(createdByA.reservationId());

        // then: B가 OFFERED 상태로 자동 승급된다
        WaitingQueuePromotion promotion = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();
        assertThat(promotion.getUser().getId()).isEqualTo(userBId);

        // when: B가 승급 기회를 수락한다
        authenticateAs(userBId);
        PromotionAcceptResponse acceptResponse = promotionService.accept(promotion.getId());
        SecurityContextHolder.clearContext();

        // then: B의 새 Reservation이 CONFIRMED 상태로 생성된다
        assertThat(acceptResponse.promotionStatus()).isEqualTo(PromotionStatus.ACCEPTED);
        Reservation newReservation = reservationRepository.findById(acceptResponse.reservationId()).orElseThrow();
        assertThat(newReservation.getUser().getId()).isEqualTo(userBId);
        assertThat(newReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(newReservation.getStartAt()).isEqualTo(startAt);
        assertThat(newReservation.getEndAt()).isEqualTo(endAt);
    }

    @Test
    @DisplayName("startAt이 이미 지난 뒤 Promotion을 수락해도, 일반 체크인 시간창이 아니라 ACCEPT 이후 1분 이내라면 체크인에 성공한다")
    void accept_AfterStartAtHasPassed_ShouldStillAllowCheckInWithinPromotionWindow() {
        // given: A가 리소스를 예약하고, B가 같은 시간대에 대기열로 등록한다
        Long resourceId = persistResourceWithPolicy("Promotion E2E Resource " + System.nanoTime());
        Long userAId = persistUser("promotion-e2e-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("promotion-e2e-user-b-" + System.nanoTime() + "@gymflow.com");
        // startAt을 과거로 두어 "Promotion ACCEPT가 startAt 이후에 발생"하는 버그 시나리오를 재현한다
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(1).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        // when: A의 예약이 NO_SHOW 처리되어 B가 승급되고, B가 수락한다 (이 시점은 이미 startAt을 지났다)
        reservationService.noShow(createdByA.reservationId());
        WaitingQueuePromotion promotion = promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();

        authenticateAs(userBId);
        PromotionAcceptResponse acceptResponse = promotionService.accept(promotion.getId());

        // then: 일반 checkIn()이라면 startAt(이미 과거)이 지나 실패했겠지만, Promotion 체크인 정책이 적용되어 성공한다
        ReservationResponse checkInResponse = reservationService.checkInReservation(acceptResponse.reservationId());
        SecurityContextHolder.clearContext();

        assertThat(checkInResponse.status()).isEqualTo(ReservationStatus.CHECKED_IN);
        Reservation checkedInReservation = reservationRepository.findById(acceptResponse.reservationId()).orElseThrow();
        assertThat(checkedInReservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(checkedInReservation.getCheckInAt()).isNotNull();
    }

    /**
     * 직전 작업(ReservationSlotLock)은 "동시에 실행되는" 요청 사이의 write-skew만 막아준다.
     * 여기서는 Active OFFERED Promotion이 이미 commit된 "이후"에 별도의 요청이 순차적으로
     * 들어오는 상황을 검증한다 - 이는 Lock이 아니라 Promotion overlap 조회 자체가 정확해야
     * 해결되는 문제이므로 동시 실행 없이 순서대로 호출한다.
     */
    @Test
    @DisplayName("Active OFFERED Promotion이 이미 commit된 뒤, 겹치는 시간대의 일반 Reservation 생성 요청이 순차적으로 들어오면 차단된다")
    void createReservation_AfterActiveOfferedPromotionAlreadyCommitted_ShouldBeBlocked() {
        // given: A가 리소스를 예약(14:00~14:15)하고 B가 대기열에 등록한 뒤, A가 NO_SHOW 처리되어
        // B가 OFFERED로 자동 승급된다. 이 시점에 Promotion은 이미 커밋되어 있다.
        Long resourceId = persistResourceWithPolicy("Sequential Overlap Resource " + System.nanoTime());
        Long userAId = persistUser("sequential-overlap-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("sequential-overlap-user-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("sequential-overlap-user-c-" + System.nanoTime() + "@gymflow.com");
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
        promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();

        // when: Promotion이 이미 commit된 뒤, C가 겹치는 14:05~14:20 시간대에 별도로(순차적으로) 예약을 시도한다
        authenticateAs(userCId);
        LocalDateTime overlappingStartAt = startAt.plusMinutes(5);

        // then
        assertThatThrownBy(() -> reservationService.createReservation(
                new ReservationCreateRequest(resourceId, overlappingStartAt, 15)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_PROMOTION_RESERVED);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Active OFFERED Promotion의 종료 시각과 정확히 맞닿는 시간대의 Reservation 생성은 순차 요청에서도 성공한다")
    void createReservation_WithTimeAdjacentToActiveOfferedPromotion_ShouldSucceed() {
        // given: A가 리소스를 예약(14:00~14:15)하고 B가 대기열에 등록한 뒤, A가 NO_SHOW 처리되어
        // B가 OFFERED로 자동 승급된다
        Long resourceId = persistResourceWithPolicy("Sequential Adjacent Resource " + System.nanoTime());
        Long userAId = persistUser("sequential-adjacent-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("sequential-adjacent-user-b-" + System.nanoTime() + "@gymflow.com");
        Long userCId = persistUser("sequential-adjacent-user-c-" + System.nanoTime() + "@gymflow.com");
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
        promotionRepository
                .findByResourceIdAndStartAtAndEndAtAndStatus(resourceId, startAt, endAt, PromotionStatus.OFFERED)
                .orElseThrow();

        // when: Promotion 종료 시각(endAt)과 정확히 맞닿는 14:15~14:30 시간대에 C가 예약을 시도한다
        authenticateAs(userCId);
        ReservationResponse createdByC =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, endAt, 15));
        SecurityContextHolder.clearContext();

        // then: 경계가 정확히 맞닿는 시간대이므로 overlap이 아니어서 정상적으로 생성된다
        assertThat(createdByC.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(createdByC.startAt()).isEqualTo(endAt);
    }

    @Test
    @DisplayName("연장하려는 구간(oldEndAt~newEndAt)에 실제로 commit된 Active OFFERED Promotion이 겹치면 연장이 순차 요청에서도 차단된다")
    void extendReservation_WithRealActiveOfferedPromotionOverlappingDelta_ShouldBeBlocked() {
        // given: A가 CHECKED_IN 예약(baseTime~baseTime+15)을 보유한 상태에서,
        // 같은 Resource의 다른 시간대(baseTime+20~baseTime+35)에서 E의 취소로 F가 실제로 OFFERED 승급된다
        Long resourceId = persistResourceWithPolicy("Sequential Extend Resource " + System.nanoTime());
        Long userAId = persistUser("sequential-extend-user-a-" + System.nanoTime() + "@gymflow.com");
        Long userEId = persistUser("sequential-extend-user-e-" + System.nanoTime() + "@gymflow.com");
        Long userFId = persistUser("sequential-extend-user-f-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime baseTime = LocalDateTime.now().plusMinutes(2).withSecond(0).withNano(0);
        LocalDateTime promotionStartAt = baseTime.plusMinutes(20);
        LocalDateTime promotionEndAt = baseTime.plusMinutes(35);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, baseTime, 15));
        ReservationResponse checkedInByA = reservationService.checkInReservation(createdByA.reservationId());
        assertThat(checkedInByA.status()).isEqualTo(ReservationStatus.CHECKED_IN);
        SecurityContextHolder.clearContext();

        authenticateAs(userEId);
        ReservationResponse createdByE = reservationService.createReservation(
                new ReservationCreateRequest(resourceId, promotionStartAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userFId);
        waitingQueueService.registerWaitingQueue(
                new WaitingQueueCreateRequest(resourceId, promotionStartAt, promotionEndAt));
        SecurityContextHolder.clearContext();

        // when: E가 예약을 취소해 F가 promotionStartAt~promotionEndAt 구간에 실제로 OFFERED 승급된다
        authenticateAs(userEId);
        reservationService.cancelReservation(
                createdByE.reservationId(), new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE));
        SecurityContextHolder.clearContext();
        promotionRepository.findByResourceIdAndStartAtAndEndAtAndStatus(
                resourceId, promotionStartAt, promotionEndAt, PromotionStatus.OFFERED).orElseThrow();

        // then: A가 15분 연장을 요청하면 delta(baseTime+15~baseTime+30)가 Promotion(baseTime+20~baseTime+35)과
        // 겹치므로 연장이 차단된다
        authenticateAs(userAId);
        assertThatThrownBy(() -> reservationService.extendReservation(
                createdByA.reservationId(), new ReservationExtensionRequest(15)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_PROMOTION_RESERVED);
        SecurityContextHolder.clearContext();

        Reservation reservationOfA = reservationRepository.findById(createdByA.reservationId()).orElseThrow();
        assertThat(reservationOfA.getEndAt()).isEqualTo(baseTime.plusMinutes(15));
    }
}
