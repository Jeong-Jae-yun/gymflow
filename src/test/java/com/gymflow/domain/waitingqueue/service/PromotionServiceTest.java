package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceAvailabilityLockRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionCheckInRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionLockRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionOfferedRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.response.PromotionAcceptResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.common.transaction.TransactionAwareLockReleaser;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    private static final Long CURRENT_USER_ID = 1L;
    private static final Long RESOURCE_ID = 10L;
    private static final Long WAITING_QUEUE_ID = 200L;
    private static final Long PROMOTION_ID = 501L;
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 12, 14, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 12, 14, 15);
    private static final ReservationSlotLockHandle SLOT_LOCK_HANDLE = new ReservationSlotLockHandle(
            List.of(new ReservationSlotLockHandle.SlotLock("test-slot-key", "slot-lock-token")));

    @Mock
    private WaitingQueuePromotionRepository promotionRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ResourceAvailabilityLockRepository resourceAvailabilityLockRepository;

    @Mock
    private PromotionLockRepository promotionLockRepository;

    @Mock
    private ReservationSlotLockRepository reservationSlotLockRepository;

    @Mock
    private PromotionOfferedRepository promotionOfferedRepository;

    @Mock
    private PromotionCheckInRepository promotionCheckInRepository;

    @Mock
    private TransactionAwareLockReleaser lockReleaser;

    @Mock
    private PromotionProcessor promotionProcessor;

    @InjectMocks
    private PromotionService promotionService;

    @BeforeEach
    void setUpSecurityContext() {
        CustomUserDetails principal = new CustomUserDetails(CURRENT_USER_ID, "test@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // reject()/handleOfferedExpiration()/handleCheckInTimeout()가 self proxy를 통해
        // 자신의 xxxTransactional()을 호출하므로, 실제 Spring 컨텍스트 없이도 그 메서드가
        // (같은 객체의) 진짜 구현을 타도록 self를 자기 자신으로 연결한다.
        ReflectionTestUtils.setField(promotionService, "self", promotionService);

        lenient().when(promotionLockRepository.tryLock(any(), any(), any()))
                .thenReturn(Optional.of("lock-token"));
        // accept()를 다루지 않는 테스트에서는 사용되지 않으므로 lenient로 등록한다
        lenient().when(reservationSlotLockRepository.tryLockAll(any(), any(), any()))
                .thenReturn(Optional.of(SLOT_LOCK_HANDLE));
        lenient().when(resourceAvailabilityLockRepository.tryLock(any()))
                .thenReturn(Optional.of("availability-lock-token"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User user(Long id) {
        User user = User.builder()
                .email("user" + id + "@gymflow.com")
                .password("securePassword123")
                .name("Tester " + id)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Resource resourceWithPolicy(int minDuration) {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", RESOURCE_ID);
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(minDuration)
                .maxDuration(60)
                .build();
        return resource;
    }

    private WaitingQueue waitingQueue(Long id, User user, Resource resource, WaitingQueueStatus status) {
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        ReflectionTestUtils.setField(waitingQueue, "id", id);
        if (status != WaitingQueueStatus.WAITING) {
            ReflectionTestUtils.setField(waitingQueue, "status", status);
        }
        return waitingQueue;
    }

    private WaitingQueuePromotion offeredPromotion(
            Long id, WaitingQueue waitingQueue, User user, Resource resource, LocalDateTime expiresAt) {
        WaitingQueuePromotion promotion = WaitingQueuePromotion.builder()
                .waitingQueue(waitingQueue)
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .offeredAt(expiresAt.minusMinutes(2))
                .expiresAt(expiresAt)
                .build();
        ReflectionTestUtils.setField(promotion, "id", id);
        return promotion;
    }

    // ===================== accept =====================

    @Test
    @DisplayName("본인의 OFFERED Promotion을 수락하면 ACCEPTED가 되고 Reservation이 CONFIRMED로 생성된다")
    void accept_WithOwnOfferedPromotion_ShouldSucceed() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(1);
        WaitingQueuePromotion promotion =
                offeredPromotion(PROMOTION_ID, waitingQueue, currentUser, resource, expiresAt);

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));
        when(promotionRepository.findById(PROMOTION_ID)).thenReturn(Optional.of(promotion));
        when(reservationRepository.existsOverlapping(
                RESOURCE_ID, ReservationStatus.OCCUPYING_STATUSES, START_AT, END_AT)).thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", 9001L);
            return reservation;
        });

        // when
        PromotionAcceptResponse response = promotionService.accept(PROMOTION_ID);

        // then
        assertThat(response.promotionId()).isEqualTo(PROMOTION_ID);
        assertThat(response.promotionStatus()).isEqualTo(PromotionStatus.ACCEPTED);
        assertThat(response.reservationId()).isEqualTo(9001L);
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.ACCEPTED);
        assertThat(promotion.getReservation()).isNotNull();
        assertThat(promotion.getReservation().getId()).isEqualTo(9001L);

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(reservationCaptor.capture());
        Reservation savedReservation = reservationCaptor.getValue();
        assertThat(savedReservation.getUser()).isSameAs(currentUser);
        assertThat(savedReservation.getResource()).isSameAs(resource);
        assertThat(savedReservation.getStartAt()).isEqualTo(START_AT);
        assertThat(savedReservation.getEndAt()).isEqualTo(END_AT);
        assertThat(savedReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        verify(promotionOfferedRepository).remove(PROMOTION_ID);
        verify(promotionCheckInRepository).register(eq(9001L), eq(Duration.ofMinutes(1)));
        verify(promotionLockRepository).unlock(eq(RESOURCE_ID), eq(START_AT), eq(END_AT), eq("lock-token"));
    }

    @Test
    @DisplayName("다른 사용자의 Promotion을 수락하면 PROMOTION_NOT_FOUND 예외가 발생한다")
    void accept_WithOtherUsersPromotion_ShouldThrowException() {
        // given
        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> promotionService.accept(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROMOTION_NOT_FOUND);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
    }

    @Test
    @DisplayName("이미 ACCEPTED/REJECTED/EXPIRED 상태의 Promotion은 재수락할 수 없다")
    void accept_WithTerminalStatus_ShouldThrowException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));
        ReflectionTestUtils.setField(promotion, "status", PromotionStatus.REJECTED);

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));

        // when & then
        assertThatThrownBy(() -> promotionService.accept(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROMOTION_NOT_OFFERED);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
    }

    @Test
    @DisplayName("expiresAt이 지난 Promotion은 수락할 수 없다")
    void accept_AfterExpiresAt_ShouldThrowException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().minusSeconds(1));

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));

        // when & then
        assertThatThrownBy(() -> promotionService.accept(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROMOTION_EXPIRED);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
    }

    @Test
    @DisplayName("다른 CONFIRMED/CHECKED_IN 예약과 겹치면 Reservation 생성에 실패한다")
    void accept_WithOverlappingReservation_ShouldThrowException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));
        when(promotionRepository.findById(PROMOTION_ID)).thenReturn(Optional.of(promotion));
        when(reservationRepository.existsOverlapping(
                RESOURCE_ID, ReservationStatus.OCCUPYING_STATUSES, START_AT, END_AT)).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> promotionService.accept(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_TIME_CONFLICT);

        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.OFFERED);
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(promotionLockRepository).unlock(eq(RESOURCE_ID), eq(START_AT), eq(END_AT), eq("lock-token"));
    }

    @Test
    @DisplayName("Promotion Lock 획득에 실패하면 RESERVATION_IN_PROGRESS 예외가 발생하고 아무 것도 변경되지 않는다")
    void accept_WithLockAcquisitionFailure_ShouldThrowException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));
        when(promotionLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> promotionService.accept(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);

        verify(reservationRepository, never()).existsOverlapping(any(), anyCollection(), any(), any());
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(promotionLockRepository, never()).unlock(any(), any(), any(), any());
        // ResourceAvailabilityLock은 이미 획득했으므로 반드시 해제되어야 한다
        verify(resourceAvailabilityLockRepository).unlock(RESOURCE_ID, "availability-lock-token");
    }

    @Test
    @DisplayName("ResourceAvailabilityLock 획득에 실패하면 RESERVATION_IN_PROGRESS 예외가 발생하고 PromotionLock을 시도하지 않는다")
    void accept_WithAvailabilityLockAcquisitionFailure_ShouldThrowException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> promotionService.accept(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);

        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(resourceAvailabilityLockRepository, never()).unlock(any(), any());
    }

    @Test
    @DisplayName("Lock 획득 순서는 ResourceAvailabilityLock -> PromotionLock -> ReservationSlotLock이고 해제는 그 역순이다")
    void accept_ShouldAcquireAndReleaseLocksInFixedOrder() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion =
                offeredPromotion(PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));
        when(promotionRepository.findById(PROMOTION_ID)).thenReturn(Optional.of(promotion));
        when(reservationRepository.existsOverlapping(
                RESOURCE_ID, ReservationStatus.OCCUPYING_STATUSES, START_AT, END_AT)).thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", 9001L);
            return reservation;
        });

        // when
        promotionService.accept(PROMOTION_ID);

        // then: ResourceAvailabilityLock -> PromotionLock -> ReservationSlotLock 순으로 획득되고 해제는 역순이다
        InOrder inOrder = inOrder(resourceAvailabilityLockRepository, promotionLockRepository, reservationSlotLockRepository);
        inOrder.verify(resourceAvailabilityLockRepository).tryLock(RESOURCE_ID);
        inOrder.verify(promotionLockRepository).tryLock(RESOURCE_ID, START_AT, END_AT);
        inOrder.verify(reservationSlotLockRepository).tryLockAll(RESOURCE_ID, START_AT, END_AT);
        inOrder.verify(reservationSlotLockRepository).unlockAll(SLOT_LOCK_HANDLE);
        inOrder.verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, END_AT, "lock-token");
        inOrder.verify(resourceAvailabilityLockRepository).unlock(RESOURCE_ID, "availability-lock-token");
    }

    @Test
    @DisplayName("ReservationSlotLock 획득에 실패하면 RESERVATION_IN_PROGRESS 예외가 발생하고 PromotionLock은 해제된다")
    void accept_WithReservationSlotLockAcquisitionFailure_ShouldThrowExceptionAndReleasePromotionLock() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion =
                offeredPromotion(PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));
        when(reservationSlotLockRepository.tryLockAll(RESOURCE_ID, START_AT, END_AT)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> promotionService.accept(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);

        verify(reservationRepository, never()).existsOverlapping(any(), anyCollection(), any(), any());
        verify(reservationRepository, never()).save(any(Reservation.class));
        // ReservationSlotLock을 획득하지 못했더라도 이미 획득한 PromotionLock은 반드시 해제되어야 한다
        verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, END_AT, "lock-token");
        verify(reservationSlotLockRepository, never()).unlockAll(any());
    }

    // ===================== reject =====================

    @Test
    @DisplayName("본인의 OFFERED Promotion을 거절하면 REJECTED가 되고 다음 대기자 승급이 시도된다")
    void reject_WithOwnOfferedPromotion_ShouldSucceedAndTriggerNextPromotion() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));
        when(promotionRepository.findById(PROMOTION_ID)).thenReturn(Optional.of(promotion));

        // when
        promotionService.reject(PROMOTION_ID);

        // then
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.REJECTED);
        verify(promotionOfferedRepository).remove(PROMOTION_ID);
        // 자신의 PromotionLock은 (transaction 없는 단위 테스트이므로 lockReleaser.register()가 false를
        // 반환해) finally에서 즉시 한 번만 해제되고, commit 이후 후속 승급은 별도 Bean인
        // PromotionProcessor에게 위임된다(self-invocation 아님)
        verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, END_AT, "lock-token");
        verify(lockReleaser).register(any());
        verify(promotionProcessor).tryPromote(RESOURCE_ID, START_AT, END_AT);
    }

    @Test
    @DisplayName("다른 사용자의 Promotion을 거절하면 PROMOTION_NOT_FOUND 예외가 발생한다")
    void reject_WithOtherUsersPromotion_ShouldThrowException() {
        // given
        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> promotionService.reject(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROMOTION_NOT_FOUND);
        verify(promotionProcessor, never()).tryPromote(any(), any(), any());
    }

    @Test
    @DisplayName("이미 terminal 상태인 Promotion은 거절할 수 없다")
    void reject_WithTerminalStatus_ShouldThrowException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusMinutes(1));
        ReflectionTestUtils.setField(promotion, "status", PromotionStatus.ACCEPTED);

        when(promotionRepository.findByIdAndUserId(PROMOTION_ID, CURRENT_USER_ID)).thenReturn(Optional.of(promotion));

        // when & then
        assertThatThrownBy(() -> promotionService.reject(PROMOTION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROMOTION_NOT_OFFERED);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
        verify(promotionProcessor, never()).tryPromote(any(), any(), any());
    }

    // ===================== checkInPromotedReservation =====================

    private WaitingQueuePromotion acceptedPromotion(
            Long id, User user, Resource resource, Reservation reservation, LocalDateTime acceptedAt) {
        WaitingQueuePromotion promotion = WaitingQueuePromotion.builder()
                .waitingQueue(waitingQueue(WAITING_QUEUE_ID, user, resource, WaitingQueueStatus.PROMOTED))
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .offeredAt(acceptedAt.minusMinutes(2))
                .expiresAt(acceptedAt.plusSeconds(1))
                .build();
        ReflectionTestUtils.setField(promotion, "id", id);
        promotion.accept(acceptedAt);
        promotion.linkReservation(reservation);
        return promotion;
    }

    private Reservation confirmedReservation(Long id, Resource resource, User user) {
        Reservation reservation = Reservation.builder()
                .reservationBatchId(java.util.UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }

    @Test
    @DisplayName("Promotion으로 생성된 Reservation은 ACCEPT 이후 1분 이내면 체크인에 성공하고 Check-In TTL이 삭제된다")
    void checkInPromotedReservation_WithinDeadline_ShouldSucceedAndRemoveTtl() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9001L, resource, currentUser);
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 8, 12, 14, 1);
        WaitingQueuePromotion promotion = acceptedPromotion(PROMOTION_ID, currentUser, resource, reservation, acceptedAt);
        when(promotionRepository.findByReservationId(9001L)).thenReturn(Optional.of(promotion));
        LocalDateTime now = acceptedAt.plusSeconds(30);

        // when
        boolean result = promotionService.checkInPromotedReservation(reservation, now);

        // then
        assertThat(result).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(reservation.getCheckInAt()).isEqualTo(now);
        verify(promotionCheckInRepository).remove(9001L);
        verify(promotionLockRepository).unlock(eq(RESOURCE_ID), eq(START_AT), eq(END_AT), eq("lock-token"));
    }

    @Test
    @DisplayName("startAt이 이미 지났어도 ACCEPT로부터 1분 이내면 Promotion 체크인에 성공한다")
    void checkInPromotedReservation_WithStartAtInPast_ShouldStillSucceedWithinDeadline() {
        // given: startAt(14:00)이 이미 지난 뒤 14:01에 ACCEPT되고 14:01:30에 체크인하는 상황
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9001L, resource, currentUser);
        LocalDateTime acceptedAt = START_AT.plusMinutes(1);
        WaitingQueuePromotion promotion = acceptedPromotion(PROMOTION_ID, currentUser, resource, reservation, acceptedAt);
        when(promotionRepository.findByReservationId(9001L)).thenReturn(Optional.of(promotion));
        LocalDateTime now = acceptedAt.plusSeconds(30);

        // when
        boolean result = promotionService.checkInPromotedReservation(reservation, now);

        // then
        assertThat(result).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("Promotion ACCEPT + 1분 경계 시각에는 체크인에 성공한다")
    void checkInPromotedReservation_AtDeadlineBoundary_ShouldSucceed() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9001L, resource, currentUser);
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 8, 12, 14, 1, 20);
        WaitingQueuePromotion promotion = acceptedPromotion(PROMOTION_ID, currentUser, resource, reservation, acceptedAt);
        when(promotionRepository.findByReservationId(9001L)).thenReturn(Optional.of(promotion));
        LocalDateTime deadline = acceptedAt.plusMinutes(1);

        // when
        boolean result = promotionService.checkInPromotedReservation(reservation, deadline);

        // then
        assertThat(result).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("Promotion 체크인 deadline을 넘기면 PROMOTION_CHECKIN_EXPIRED BusinessException이 발생하고 Reservation은 CONFIRMED로 유지된다")
    void checkInPromotedReservation_AfterDeadline_ShouldThrowBusinessException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9001L, resource, currentUser);
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 8, 12, 14, 1, 20);
        WaitingQueuePromotion promotion = acceptedPromotion(PROMOTION_ID, currentUser, resource, reservation, acceptedAt);
        when(promotionRepository.findByReservationId(9001L)).thenReturn(Optional.of(promotion));
        LocalDateTime afterDeadline = acceptedAt.plusMinutes(1).plusSeconds(1);

        // when & then: 서버 오류(500)가 아니라 의미 있는 Business Error(409)로 변환되어야 한다
        assertThatThrownBy(() -> promotionService.checkInPromotedReservation(reservation, afterDeadline))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROMOTION_CHECKIN_EXPIRED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(promotionCheckInRepository, never()).remove(any());
        verify(promotionLockRepository).unlock(eq(RESOURCE_ID), eq(START_AT), eq(END_AT), eq("lock-token"));
    }

    @Test
    @DisplayName("Reservation에 연결된 Promotion이 없으면 false를 반환해 일반 체크인 정책으로 넘긴다")
    void checkInPromotedReservation_WithoutLinkedPromotion_ShouldReturnFalse() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9002L, resource, currentUser);
        when(promotionRepository.findByReservationId(9002L)).thenReturn(Optional.empty());

        // when
        boolean result = promotionService.checkInPromotedReservation(reservation, LocalDateTime.now());

        // then
        assertThat(result).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
    }

    @Test
    @DisplayName("연결된 Promotion이 ACCEPTED 상태가 아니면 Promotion 체크인 정책을 적용하지 않는다")
    void checkInPromotedReservation_WithNonAcceptedPromotion_ShouldReturnFalse() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9001L, resource, currentUser);
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 8, 12, 14, 1);
        WaitingQueuePromotion promotion = acceptedPromotion(PROMOTION_ID, currentUser, resource, reservation, acceptedAt);
        ReflectionTestUtils.setField(promotion, "status", PromotionStatus.REJECTED);
        when(promotionRepository.findByReservationId(9001L)).thenReturn(Optional.of(promotion));

        // when
        boolean result = promotionService.checkInPromotedReservation(reservation, acceptedAt.plusSeconds(10));

        // then
        assertThat(result).isFalse();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
    }

    @Test
    @DisplayName("Promotion Lock 획득에 실패하면 RESERVATION_IN_PROGRESS 예외가 발생한다")
    void checkInPromotedReservation_WithLockAcquisitionFailure_ShouldThrowException() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9001L, resource, currentUser);
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 8, 12, 14, 1);
        WaitingQueuePromotion promotion = acceptedPromotion(PROMOTION_ID, currentUser, resource, reservation, acceptedAt);
        when(promotionRepository.findByReservationId(9001L)).thenReturn(Optional.of(promotion));
        when(promotionLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> promotionService.checkInPromotedReservation(reservation, acceptedAt.plusSeconds(10)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Promotion Check-In TTL 삭제가 실패해도 체크인 자체는 성공한다")
    void checkInPromotedReservation_WithTtlRemovalFailure_ShouldStillSucceed() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        Reservation reservation = confirmedReservation(9001L, resource, currentUser);
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 8, 12, 14, 1);
        WaitingQueuePromotion promotion = acceptedPromotion(PROMOTION_ID, currentUser, resource, reservation, acceptedAt);
        when(promotionRepository.findByReservationId(9001L)).thenReturn(Optional.of(promotion));
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(promotionCheckInRepository).remove(9001L);

        // when
        boolean result = promotionService.checkInPromotedReservation(reservation, acceptedAt.plusSeconds(10));

        // then: Redis 장애와 무관하게 CHECKED_IN(source of truth)은 그대로 유지된다
        assertThat(result).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    // ===================== handleCheckInTimeout =====================

    @Test
    @DisplayName("체크인 TTL 만료 시 CONFIRMED 예약은 PROMOTION_CHECKIN_TIMEOUT 사유로 취소되고 다음 대기자 승급이 시도된다")
    void handleCheckInTimeout_WithConfirmedReservation_ShouldCancelAndTryPromote() {
        // given
        Resource resource = resourceWithPolicy(10);
        Reservation reservation = Reservation.builder()
                .reservationBatchId(java.util.UUID.randomUUID())
                .user(user(CURRENT_USER_ID))
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        ReflectionTestUtils.setField(reservation, "id", 9001L);
        when(reservationRepository.findById(9001L)).thenReturn(Optional.of(reservation));

        // when
        promotionService.handleCheckInTimeout(9001L);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelReason()).isEqualTo(CancelReason.PROMOTION_CHECKIN_TIMEOUT);
        // 자신의 PromotionLock은 (transaction 없는 단위 테스트이므로 lockReleaser.register()가 false를
        // 반환해) finally에서 즉시 한 번만 해제되고, commit 이후 후속 승급은 별도 Bean인
        // PromotionProcessor에게 위임된다(self-invocation 아님)
        verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, END_AT, "lock-token");
        verify(lockReleaser).register(any());
        verify(promotionProcessor).tryPromote(RESOURCE_ID, START_AT, END_AT);
    }

    @Test
    @DisplayName("체크인 TTL 만료 처리 중 Promotion Lock 획득에 실패하면 취소 없이 건너뛴다")
    void handleCheckInTimeout_WithLockAcquisitionFailure_ShouldSkip() {
        // given
        Resource resource = resourceWithPolicy(10);
        Reservation reservation = Reservation.builder()
                .reservationBatchId(java.util.UUID.randomUUID())
                .user(user(CURRENT_USER_ID))
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        ReflectionTestUtils.setField(reservation, "id", 9001L);
        when(reservationRepository.findById(9001L)).thenReturn(Optional.of(reservation));
        when(promotionLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT)).thenReturn(Optional.empty());

        // when
        promotionService.handleCheckInTimeout(9001L);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(promotionLockRepository, never()).unlock(any(), any(), any(), any());
        verify(promotionProcessor, never()).tryPromote(any(), any(), any());
    }

    @Test
    @DisplayName("이미 CHECKED_IN 상태인 예약은 체크인 TTL 만료 처리를 건너뛴다")
    void handleCheckInTimeout_WithAlreadyCheckedInReservation_ShouldSkip() {
        // given
        Resource resource = resourceWithPolicy(10);
        Reservation reservation = Reservation.builder()
                .reservationBatchId(java.util.UUID.randomUUID())
                .user(user(CURRENT_USER_ID))
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        ReflectionTestUtils.setField(reservation, "id", 9001L);
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findById(9001L)).thenReturn(Optional.of(reservation));

        // when
        promotionService.handleCheckInTimeout(9001L);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
        verify(promotionProcessor, never()).tryPromote(any(), any(), any());
    }

    // ===================== handleOfferedExpiration =====================

    @Test
    @DisplayName("OFFERED Promotion의 TTL이 만료되면 EXPIRED로 전이되고 다음 대기자 승급이 시도된다")
    void handleOfferedExpiration_WithOfferedPromotion_ShouldExpireAndTryPromote() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusSeconds(1));

        when(promotionRepository.findById(PROMOTION_ID)).thenReturn(Optional.of(promotion));

        // when
        promotionService.handleOfferedExpiration(PROMOTION_ID);

        // then
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.EXPIRED);
        // 자신의 PromotionLock은 (transaction 없는 단위 테스트이므로 lockReleaser.register()가 false를
        // 반환해) finally에서 즉시 한 번만 해제되고, commit 이후 후속 승급은 별도 Bean인
        // PromotionProcessor에게 위임된다(self-invocation 아님)
        verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, END_AT, "lock-token");
        verify(lockReleaser).register(any());
        verify(promotionProcessor).tryPromote(RESOURCE_ID, START_AT, END_AT);
    }

    @Test
    @DisplayName("이미 ACCEPTED 상태인 Promotion은 TTL 만료 처리를 건너뛴다")
    void handleOfferedExpiration_WithAlreadyAcceptedPromotion_ShouldSkip() {
        // given
        Resource resource = resourceWithPolicy(10);
        User currentUser = user(CURRENT_USER_ID);
        WaitingQueue waitingQueue = waitingQueue(WAITING_QUEUE_ID, currentUser, resource, WaitingQueueStatus.PROMOTED);
        WaitingQueuePromotion promotion = offeredPromotion(
                PROMOTION_ID, waitingQueue, currentUser, resource, LocalDateTime.now().plusSeconds(1));
        ReflectionTestUtils.setField(promotion, "status", PromotionStatus.ACCEPTED);

        when(promotionRepository.findById(PROMOTION_ID)).thenReturn(Optional.of(promotion));

        // when
        promotionService.handleOfferedExpiration(PROMOTION_ID);

        // then
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.ACCEPTED);
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
        verify(promotionProcessor, never()).tryPromote(any(), any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 Promotion에 대한 TTL 만료는 예외 없이 안전하게 종료된다")
    void handleOfferedExpiration_WithNonExistentPromotion_ShouldDoNothing() {
        // given
        when(promotionRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        promotionService.handleOfferedExpiration(999L);

        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
        verify(promotionProcessor, never()).tryPromote(any(), any(), any());
    }

    @Test
    @DisplayName("hasActiveOfferedPromotion은 만료되지 않은 OFFERED Promotion이 있으면 true를 반환한다")
    void hasActiveOfferedPromotion_WithActiveOffer_ShouldReturnTrue() {
        // given
        LocalDateTime now = LocalDateTime.now();
        when(promotionRepository.existsByResourceIdAndStatusAndExpiresAtAfter(RESOURCE_ID, PromotionStatus.OFFERED, now))
                .thenReturn(true);

        // when
        boolean result = promotionService.hasActiveOfferedPromotion(RESOURCE_ID, now);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("hasActiveOfferedPromotion은 활성 OFFERED Promotion이 없으면 false를 반환한다")
    void hasActiveOfferedPromotion_WithoutActiveOffer_ShouldReturnFalse() {
        // given
        LocalDateTime now = LocalDateTime.now();
        when(promotionRepository.existsByResourceIdAndStatusAndExpiresAtAfter(RESOURCE_ID, PromotionStatus.OFFERED, now))
                .thenReturn(false);

        // when
        boolean result = promotionService.hasActiveOfferedPromotion(RESOURCE_ID, now);

        // then
        assertThat(result).isFalse();
    }
}
