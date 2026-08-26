package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceAvailabilityLockRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionLockRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionOfferedRepository;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueEventPublisher;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueuePromotedEvent;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRedisRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import com.gymflow.global.common.transaction.TransactionAwareLockReleaser;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PromotionService.tryPromote()에서 분리된 PromotionProcessor의 자동 승급 로직을 검증한다.
 * PromotionService의 self-invocation 제거를 위해 별도 Bean으로 옮겨진 것으로, 여기서 검증하는
 * 비즈니스 규칙(Lock hierarchy, Redis Fail-Open, TTL 계산 등)은 이전과 동일하다.
 */
@ExtendWith(MockitoExtension.class)
class PromotionProcessorTest {

    private static final Long RESOURCE_ID = 10L;
    private static final Long WAITING_QUEUE_ID = 200L;
    private static final Long PROMOTION_ID = 501L;
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 12, 14, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 12, 14, 15);
    private static final ReservationSlotLockHandle SLOT_LOCK_HANDLE = new ReservationSlotLockHandle(
            List.of(new ReservationSlotLockHandle.SlotLock("test-slot-key", "slot-lock-token")));

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ResourceAvailabilityLockRepository resourceAvailabilityLockRepository;

    @Mock
    private PromotionLockRepository promotionLockRepository;

    @Mock
    private ReservationSlotLockRepository reservationSlotLockRepository;

    @Mock
    private WaitingQueuePromotionRepository promotionRepository;

    @Mock
    private WaitingQueueRepository waitingQueueRepository;

    @Mock
    private WaitingQueueRedisRepository waitingQueueRedisRepository;

    @Mock
    private PromotionOfferedRepository promotionOfferedRepository;

    @Mock
    private WaitingQueueEventPublisher waitingQueueEventPublisher;

    @Mock
    private TransactionAwareLockReleaser lockReleaser;

    @InjectMocks
    private PromotionProcessor promotionProcessor;

    @BeforeEach
    void setUp() {
        lenient().when(promotionLockRepository.tryLock(any(), any(), any()))
                .thenReturn(Optional.of("lock-token"));
        lenient().when(reservationSlotLockRepository.tryLockAll(any(), any(), any()))
                .thenReturn(Optional.of(SLOT_LOCK_HANDLE));
        lenient().when(resourceAvailabilityLockRepository.tryLock(any()))
                .thenReturn(Optional.of("availability-lock-token"));
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

    private void stubPromotionSave() {
        when(promotionRepository.save(any(WaitingQueuePromotion.class))).thenAnswer(invocation -> {
            WaitingQueuePromotion promotion = invocation.getArgument(0);
            ReflectionTestUtils.setField(promotion, "id", PROMOTION_ID);
            return promotion;
        });
    }

    @Test
    @DisplayName("빈 슬롯에 WAITING 대기자가 있으면 1순위를 OFFERED로 승급시킨다")
    void tryPromote_WithWaitingCandidate_ShouldOfferToFirstCandidate() {
        // given
        Resource resource = resourceWithPolicy(10);
        User candidateUser = user(2L);
        WaitingQueue candidate = waitingQueue(WAITING_QUEUE_ID, candidateUser, resource, WaitingQueueStatus.WAITING);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).thenReturn(List.of(WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(candidate));
        stubPromotionSave();

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then
        assertThat(candidate.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
        ArgumentCaptor<WaitingQueuePromotion> captor = ArgumentCaptor.forClass(WaitingQueuePromotion.class);
        verify(promotionRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(candidateUser);
        assertThat(captor.getValue().getStatus()).isEqualTo(PromotionStatus.OFFERED);
        verify(waitingQueueRedisRepository).remove(WAITING_QUEUE_ID, RESOURCE_ID, START_AT);
        verify(waitingQueueEventPublisher).publish(any(WaitingQueuePromotedEvent.class));
        verify(promotionLockRepository).unlock(eq(RESOURCE_ID), eq(START_AT), eq(endAt), eq("lock-token"));
    }

    @Test
    @DisplayName("이미 활성 OFFERED Promotion이 있으면 추가로 승급시키지 않는다")
    void tryPromote_WithExistingActiveOffer_ShouldNotCreateAnother() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(true);

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then
        verify(waitingQueueRepository, never()).findById(any());
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
        verify(promotionLockRepository).unlock(eq(RESOURCE_ID), eq(START_AT), eq(endAt), eq("lock-token"));
    }

    @Test
    @DisplayName("기존 OFFERED와 시간이 겹치는(정확히 일치하지 않는) 새 후보 구간은 승급시키지 않는다")
    void tryPromote_WithOverlappingButNotExactExistingOffer_ShouldNotCreateAnother() {
        // given: 기존 OFFERED 14:00~14:20, 새 후보 구간 14:10~14:25 (정확히 일치하지 않지만 겹침)
        Resource resource = resourceWithPolicy(10);
        LocalDateTime candidateStart = START_AT.plusMinutes(10);
        LocalDateTime candidateEnd = START_AT.plusMinutes(25);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, candidateStart, candidateEnd)).thenReturn(true);

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, candidateStart, candidateEnd);

        // then
        verify(waitingQueueRepository, never()).findById(any());
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
    }

    @Test
    @DisplayName("기존 OFFERED와 시간이 겹치지 않고 경계만 맞닿으면 새로운 승급을 시도한다")
    void tryPromote_WithAdjacentExistingOffer_ShouldStillAttemptPromotion() {
        // given: 기존 OFFERED 14:00~14:15, 새 후보 구간 14:15~14:30 (경계만 맞닿아 overlap 아님)
        // candidateEnd는 minDuration 검증(remaining = endAt - now)을 통과하도록 미래 시각으로 둔다
        Resource resource = resourceWithPolicy(10);
        LocalDateTime candidateStart = END_AT;
        LocalDateTime candidateEnd = LocalDateTime.now().plusMinutes(15);
        WaitingQueue candidate = waitingQueue(WAITING_QUEUE_ID, user(2L), resource, WaitingQueueStatus.WAITING);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, candidateStart, candidateEnd)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, candidateStart)).thenReturn(List.of(WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(candidate));
        stubPromotionSave();

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, candidateStart, candidateEnd);

        // then
        assertThat(candidate.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
        verify(promotionRepository).save(any(WaitingQueuePromotion.class));
    }

    @Test
    @DisplayName("겹치는 시간대의 Promotion이 REJECTED/EXPIRED/ACCEPTED 등 terminal 상태이면 새로운 승급을 시도한다")
    void tryPromote_WithOverlappingTerminalPromotion_ShouldStillAttemptPromotion() {
        // given: existsOverlapping은 OFFERED만 조회하므로 terminal 상태의 Promotion은 대상에서 제외된다
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);
        WaitingQueue candidate = waitingQueue(WAITING_QUEUE_ID, user(2L), resource, WaitingQueueStatus.WAITING);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).thenReturn(List.of(WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(candidate));
        stubPromotionSave();

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then
        assertThat(candidate.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
        verify(promotionRepository).save(any(WaitingQueuePromotion.class));
    }

    @Test
    @DisplayName("대기자가 없으면 아무 작업도 하지 않는다")
    void tryPromote_WithNoWaitingCandidate_ShouldDoNothing() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).thenReturn(List.of());

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
        verify(waitingQueueEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("Redis 대기열 조회(findAll)가 실패해도 예외가 전파되지 않고 이번 승급 시도만 건너뛴다")
    void tryPromote_WithWaitingQueueRedisFindAllFailure_ShouldSkipPromotionWithoutPropagatingException() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT))
                .thenThrow(new RedisConnectionFailureException("연결 실패"));

        // when & then: findAll 실패는 warn 로그만 남기고 예외 없이 종료되어야 한다
        assertThatCode(() -> promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt))
                .doesNotThrowAnyException();

        // then: 승급 후보를 알 수 없으므로 새로운 Promotion을 생성하지 않는다
        verify(waitingQueueRepository, never()).findById(any());
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
        verify(waitingQueueEventPublisher, never()).publish(any());
        // 이미 획득한 PromotionLock은 정상적으로 해제된다
        verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, endAt, "lock-token");
    }

    @Test
    @DisplayName("남은 이용시간이 minDuration 이하이면 승급하지 않는다")
    void tryPromote_WithRemainingTimeAtOrBelowMinDuration_ShouldNotPromote() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(10);
        WaitingQueue candidate = waitingQueue(WAITING_QUEUE_ID, user(2L), resource, WaitingQueueStatus.WAITING);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).thenReturn(List.of(WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(candidate));

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then: 남은시간(10분) - minDuration(10분) <= 0 이므로 승급하지 않는다
        assertThat(candidate.getStatus()).isEqualTo(WaitingQueueStatus.WAITING);
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
    }

    @Test
    @DisplayName("Promotion Lock 획득에 실패하면 승급을 건너뛴다")
    void tryPromote_WithLockAcquisitionFailure_ShouldSkip() {
        // given
        Resource resource = resourceWithPolicy(10);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionLockRepository.tryLock(RESOURCE_ID, START_AT, END_AT)).thenReturn(Optional.empty());

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, END_AT);

        // then
        verify(promotionRepository, never())
                .existsOverlapping(any(), any(), any(), any());
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
        verify(promotionLockRepository, never()).unlock(any(), any(), any(), any());
        // ResourceAvailabilityLock은 이미 획득했으므로 반드시 해제되어야 한다
        verify(resourceAvailabilityLockRepository).unlock(RESOURCE_ID, "availability-lock-token");
    }

    @Test
    @DisplayName("ResourceAvailabilityLock 획득에 실패하면 승급을 건너뛰고 Resource 조회조차 하지 않는다")
    void tryPromote_WithAvailabilityLockAcquisitionFailure_ShouldSkip() {
        // given
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.empty());

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, END_AT);

        // then
        verify(resourceRepository, never()).findWithReservationPolicyById(any());
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
        verify(resourceAvailabilityLockRepository, never()).unlock(any(), any());
    }

    @Test
    @DisplayName("Resource가 ACTIVE 상태가 아니면 승급을 시도하지 않는다")
    void tryPromote_WithInactiveResource_ShouldSkip() {
        // given
        Resource resource = resourceWithPolicy(10);
        ReflectionTestUtils.setField(resource, "status", ResourceStatus.MAINTENANCE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, END_AT);

        // then
        verify(promotionLockRepository, never()).tryLock(any(), any(), any());
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
        // Resource 상태 확인 이후에도 ResourceAvailabilityLock은 반드시 해제되어야 한다
        verify(resourceAvailabilityLockRepository).unlock(RESOURCE_ID, "availability-lock-token");
    }

    @Test
    @DisplayName("Lock 획득 순서는 ResourceAvailabilityLock -> PromotionLock -> ReservationSlotLock이고 해제는 그 역순이다")
    void tryPromote_ShouldAcquireAndReleaseLocksInFixedOrder() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);
        WaitingQueue candidate = waitingQueue(WAITING_QUEUE_ID, user(2L), resource, WaitingQueueStatus.WAITING);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).thenReturn(List.of(WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(candidate));
        stubPromotionSave();

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then
        InOrder inOrder = inOrder(resourceAvailabilityLockRepository, promotionLockRepository, reservationSlotLockRepository);
        inOrder.verify(resourceAvailabilityLockRepository).tryLock(RESOURCE_ID);
        inOrder.verify(promotionLockRepository).tryLock(RESOURCE_ID, START_AT, endAt);
        inOrder.verify(reservationSlotLockRepository).tryLockAll(RESOURCE_ID, START_AT, endAt);
        inOrder.verify(reservationSlotLockRepository).unlockAll(SLOT_LOCK_HANDLE);
        inOrder.verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, endAt, "lock-token");
        inOrder.verify(resourceAvailabilityLockRepository).unlock(RESOURCE_ID, "availability-lock-token");
    }

    @Test
    @DisplayName("ReservationSlotLock 획득에 실패하면 승급을 건너뛰지만 PromotionLock은 해제된다")
    void tryPromote_WithReservationSlotLockAcquisitionFailure_ShouldSkipButReleasePromotionLock() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);
        WaitingQueue candidate = waitingQueue(WAITING_QUEUE_ID, user(2L), resource, WaitingQueueStatus.WAITING);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).thenReturn(List.of(WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(candidate));
        when(reservationSlotLockRepository.tryLockAll(RESOURCE_ID, START_AT, endAt)).thenReturn(Optional.empty());

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then: 후보가 있어도 겹치는 시간대의 일반 예약 생성 등과 Slot Lock이 경합 중이면 승급을 건너뛴다
        assertThat(candidate.getStatus()).isEqualTo(WaitingQueueStatus.WAITING);
        verify(promotionRepository, never()).save(any(WaitingQueuePromotion.class));
        verify(waitingQueueEventPublisher, never()).publish(any());
        // ReservationSlotLock을 획득하지 못했더라도 이미 획득한 PromotionLock은 반드시 해제되어야 한다
        verify(promotionLockRepository).unlock(RESOURCE_ID, START_AT, endAt, "lock-token");
        verify(reservationSlotLockRepository, never()).unlockAll(any());
    }

    @Test
    @DisplayName("Redis OFFERED TTL 등록이 실패해도 MySQL Promotion은 유지된다")
    void tryPromote_WithOfferedTtlRegistrationFailure_ShouldStillPersistPromotion() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);
        WaitingQueue candidate = waitingQueue(WAITING_QUEUE_ID, user(2L), resource, WaitingQueueStatus.WAITING);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).thenReturn(List.of(WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(candidate));
        stubPromotionSave();
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(promotionOfferedRepository).register(eq(PROMOTION_ID), any(Duration.class));

        // when & then: 예외가 전파되지 않고 정상적으로 종료된다
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        verify(promotionRepository).save(any(WaitingQueuePromotion.class));
        assertThat(candidate.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
    }

    @Test
    @DisplayName("WAITING이 아닌 stale ZSET 항목은 건너뛰고 다음 대기자를 승급시킨다")
    void tryPromote_WithStaleFirstCandidate_ShouldSkipToNextWaitingCandidate() {
        // given
        Resource resource = resourceWithPolicy(10);
        LocalDateTime endAt = LocalDateTime.now().plusMinutes(15);
        WaitingQueue cancelledFirst = waitingQueue(300L, user(2L), resource, WaitingQueueStatus.CANCELLED);
        WaitingQueue secondCandidate = waitingQueue(WAITING_QUEUE_ID, user(3L), resource, WaitingQueueStatus.WAITING);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(promotionRepository.existsOverlapping(
                RESOURCE_ID, PromotionStatus.OFFERED, START_AT, endAt)).thenReturn(false);
        when(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT))
                .thenReturn(List.of(300L, WAITING_QUEUE_ID));
        when(waitingQueueRepository.findById(300L)).thenReturn(Optional.of(cancelledFirst));
        when(waitingQueueRepository.findById(WAITING_QUEUE_ID)).thenReturn(Optional.of(secondCandidate));
        stubPromotionSave();

        // when
        promotionProcessor.tryPromote(RESOURCE_ID, START_AT, endAt);

        // then
        verify(waitingQueueRedisRepository).remove(300L, RESOURCE_ID, START_AT);
        assertThat(secondCandidate.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
    }
}
