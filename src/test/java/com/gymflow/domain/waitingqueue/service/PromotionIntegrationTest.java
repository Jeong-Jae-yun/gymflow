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
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.PromotionAcceptResponse;
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
}
