package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueEventPublisher;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueuePromotedEvent;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRedisRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitingQueueServiceTest {

    private static final Long CURRENT_USER_ID = 1L;
    private static final Long RESOURCE_ID = 10L;
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 12, 14, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 12, 14, 15);

    @Mock
    private WaitingQueueRepository waitingQueueRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WaitingQueueRedisRepository waitingQueueRedisRepository;

    @Mock
    private WaitingQueueEventPublisher waitingQueueEventPublisher;

    @InjectMocks
    private WaitingQueueService waitingQueueService;

    @BeforeEach
    void setUpSecurityContext() {
        CustomUserDetails principal = new CustomUserDetails(CURRENT_USER_ID, "test@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Resource activeResource() {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", RESOURCE_ID);
        return resource;
    }

    private User user() {
        User user = User.builder()
                .email("test@gymflow.com")
                .password("securePassword123")
                .name("John Doe")
                .build();
        ReflectionTestUtils.setField(user, "id", CURRENT_USER_ID);
        return user;
    }

    private WaitingQueue waitingQueue(Long id, User user, Resource resource) {
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        ReflectionTestUtils.setField(waitingQueue, "id", id);
        return waitingQueue;
    }

    private void stubSave() {
        when(waitingQueueRepository.save(any(WaitingQueue.class))).thenAnswer(invocation -> {
            WaitingQueue waitingQueue = invocation.getArgument(0);
            ReflectionTestUtils.setField(waitingQueue, "id", 100L);
            return waitingQueue;
        });
    }

    private void stubNoDuplicateAndUnavailable() {
        when(waitingQueueRepository.existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
                eq(CURRENT_USER_ID), eq(RESOURCE_ID), eq(START_AT), eq(END_AT), eq(WaitingQueueStatus.WAITING)))
                .thenReturn(false);
        when(reservationRepository.existsOverlapping(RESOURCE_ID, ReservationStatus.CONFIRMED, START_AT, END_AT))
                .thenReturn(true);
    }

    @Test
    @DisplayName("정상적인 요청이면 WaitingQueue를 생성하고 WaitingQueueResponse를 반환한다")
    void registerWaitingQueue_WithValidRequest_ShouldSucceed() {
        // given
        Resource resource = activeResource();
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(RESOURCE_ID, START_AT, END_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        stubNoDuplicateAndUnavailable();
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();
        when(waitingQueueRedisRepository.rank(eq(100L), eq(RESOURCE_ID), eq(START_AT)))
                .thenReturn(Optional.of(1L));

        // when
        WaitingQueueResponse response = waitingQueueService.registerWaitingQueue(request);

        // then
        assertThat(response.waitingQueueId()).isEqualTo(100L);
        assertThat(response.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(response.startAt()).isEqualTo(START_AT);
        assertThat(response.endAt()).isEqualTo(END_AT);
        assertThat(response.status()).isEqualTo(WaitingQueueStatus.WAITING);
        assertThat(response.waitingRank()).isEqualTo(2L);
        verify(waitingQueueRedisRepository).add(eq(100L), eq(RESOURCE_ID), eq(START_AT), any());
    }

    @Test
    @DisplayName("Redis 등록이 실패해도 MySQL 저장은 유지되고 waitingRank는 null로 응답한다")
    void registerWaitingQueue_WithRedisFailure_ShouldStillSucceedWithNullRank() {
        // given
        Resource resource = activeResource();
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(RESOURCE_ID, START_AT, END_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        stubNoDuplicateAndUnavailable();
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(waitingQueueRedisRepository).add(eq(100L), eq(RESOURCE_ID), eq(START_AT), any());

        // when
        WaitingQueueResponse response = waitingQueueService.registerWaitingQueue(request);

        // then
        assertThat(response.waitingQueueId()).isEqualTo(100L);
        assertThat(response.waitingRank()).isNull();
        verify(waitingQueueRepository).save(any(WaitingQueue.class));
    }

    @Test
    @DisplayName("존재하지 않는 Resource에 대기열을 등록하면 예외가 발생한다")
    void registerWaitingQueue_WithNonExistentResource_ShouldThrowException() {
        // given
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(RESOURCE_ID, START_AT, END_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> waitingQueueService.registerWaitingQueue(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(waitingQueueRepository, never()).save(any(WaitingQueue.class));
    }

    @Test
    @DisplayName("ACTIVE 상태가 아닌 Resource에 대기열을 등록하면 예외가 발생한다")
    void registerWaitingQueue_WithInactiveResource_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        ReflectionTestUtils.setField(resource, "status", ResourceStatus.INACTIVE);
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(RESOURCE_ID, START_AT, END_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> waitingQueueService.registerWaitingQueue(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_ACTIVE);

        verify(waitingQueueRepository, never()).save(any(WaitingQueue.class));
    }

    @Test
    @DisplayName("startAt이 endAt보다 이후이면 예외가 발생한다")
    void registerWaitingQueue_WithInvalidTimeRange_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        WaitingQueueCreateRequest request =
                new WaitingQueueCreateRequest(RESOURCE_ID, END_AT, START_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> waitingQueueService.registerWaitingQueue(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_WAITING_QUEUE_TIME_RANGE);

        verify(waitingQueueRepository, never()).save(any(WaitingQueue.class));
    }

    @Test
    @DisplayName("동일한 사용자/Resource/시간대에 이미 WAITING 상태의 대기열이 있으면 예외가 발생한다")
    void registerWaitingQueue_WithDuplicateWaitingEntry_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(RESOURCE_ID, START_AT, END_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(waitingQueueRepository.existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
                CURRENT_USER_ID, RESOURCE_ID, START_AT, END_AT, WaitingQueueStatus.WAITING))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() -> waitingQueueService.registerWaitingQueue(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_ALREADY_EXISTS);

        verify(waitingQueueRepository, never()).save(any(WaitingQueue.class));
    }

    @Test
    @DisplayName("이미 예약 가능한 시간대이면 대기열 등록에 실패한다")
    void registerWaitingQueue_WithAvailableTimeSlot_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(RESOURCE_ID, START_AT, END_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(waitingQueueRepository.existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
                CURRENT_USER_ID, RESOURCE_ID, START_AT, END_AT, WaitingQueueStatus.WAITING))
                .thenReturn(false);
        when(reservationRepository.existsOverlapping(RESOURCE_ID, ReservationStatus.CONFIRMED, START_AT, END_AT))
                .thenReturn(false);

        // when & then
        assertThatThrownBy(() -> waitingQueueService.registerWaitingQueue(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_AVAILABLE);

        verify(waitingQueueRepository, never()).save(any(WaitingQueue.class));
    }

    @Test
    @DisplayName("현재 로그인한 사용자의 대기열 목록을 Page 형태로 반환한다")
    void getMyWaitingQueues_ShouldReturnPageOfCurrentUsersWaitingQueues() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        Pageable pageable = PageRequest.of(0, 10);
        Page<WaitingQueue> page = new PageImpl<>(List.of(waitingQueue), pageable, 1);
        when(waitingQueueRepository.findAllByUserId(CURRENT_USER_ID, pageable)).thenReturn(page);
        when(waitingQueueRedisRepository.rank(eq(100L), eq(RESOURCE_ID), eq(START_AT)))
                .thenReturn(Optional.of(2L));

        // when
        Page<WaitingQueueResponse> response = waitingQueueService.getMyWaitingQueues(pageable);

        // then
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent().get(0).waitingQueueId()).isEqualTo(100L);
        assertThat(response.getContent().get(0).resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(response.getContent().get(0).waitingRank()).isEqualTo(3L);
        verify(waitingQueueRepository).findAllByUserId(CURRENT_USER_ID, pageable);
    }

    @Test
    @DisplayName("다른 사용자의 대기열은 조회되지 않는다")
    void getMyWaitingQueues_ShouldNotReturnOtherUsersWaitingQueues() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        Page<WaitingQueue> emptyPage = new PageImpl<>(List.of(), pageable, 0);
        when(waitingQueueRepository.findAllByUserId(CURRENT_USER_ID, pageable)).thenReturn(emptyPage);

        // when
        Page<WaitingQueueResponse> response = waitingQueueService.getMyWaitingQueues(pageable);

        // then
        assertThat(response.getContent()).isEmpty();
    }

    @Test
    @DisplayName("WAITING 상태의 대기열은 정상적으로 취소된다")
    void cancelWaitingQueue_WithWaitingStatus_ShouldSucceed() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        when(waitingQueueRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(waitingQueue));

        // when
        waitingQueueService.cancelWaitingQueue(100L);

        // then
        assertThat(waitingQueue.getStatus()).isEqualTo(WaitingQueueStatus.CANCELLED);
        verify(waitingQueueRedisRepository).remove(100L, RESOURCE_ID, START_AT);
    }

    @Test
    @DisplayName("존재하지 않는 대기열을 취소하면 예외가 발생한다")
    void cancelWaitingQueue_WithNonExistentWaitingQueue_ShouldThrowException() {
        // given
        when(waitingQueueRepository.findByIdAndUserId(999L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> waitingQueueService.cancelWaitingQueue(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 대기열을 취소하면 예외가 발생한다")
    void cancelWaitingQueue_WithOtherUsersWaitingQueue_ShouldThrowException() {
        // given
        // findByIdAndUserId가 이미 소유자 기준으로 필터링하므로 타인의 대기열은 조회되지 않는다
        when(waitingQueueRepository.findByIdAndUserId(200L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> waitingQueueService.cancelWaitingQueue(200L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 CANCELLED 상태인 대기열은 다시 취소할 수 없다")
    void cancelWaitingQueue_WithAlreadyCancelledStatus_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        waitingQueue.cancel();
        when(waitingQueueRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(waitingQueue));

        // when & then
        assertThatThrownBy(() -> waitingQueueService.cancelWaitingQueue(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("PROMOTED 상태인 대기열은 취소할 수 없다")
    void cancelWaitingQueue_WithPromotedStatus_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        waitingQueue.promote();
        when(waitingQueueRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(waitingQueue));

        // when & then
        assertThatThrownBy(() -> waitingQueueService.cancelWaitingQueue(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("대기열 등록 시 WaitingQueueRepository에 전달되는 값이 요청과 일치한다")
    void registerWaitingQueue_ShouldPassCorrectValuesToRepository() {
        // given
        Resource resource = activeResource();
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(RESOURCE_ID, START_AT, END_AT);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        stubNoDuplicateAndUnavailable();
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        waitingQueueService.registerWaitingQueue(request);

        // then
        ArgumentCaptor<WaitingQueue> captor = ArgumentCaptor.forClass(WaitingQueue.class);
        verify(waitingQueueRepository).save(captor.capture());
        assertThat(captor.getValue().getStartAt()).isEqualTo(START_AT);
        assertThat(captor.getValue().getEndAt()).isEqualTo(END_AT);
        assertThat(captor.getValue().getStatus()).isEqualTo(WaitingQueueStatus.WAITING);
    }

    @Test
    @DisplayName("WAITING 상태의 대기열은 정상적으로 승급되고 승급 이벤트가 발행된다")
    void promoteWaitingQueue_WithWaitingStatus_ShouldSucceedAndPublishEvent() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        when(waitingQueueRepository.findById(100L)).thenReturn(Optional.of(waitingQueue));

        // when
        waitingQueueService.promoteWaitingQueue(100L);

        // then
        assertThat(waitingQueue.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
        verify(waitingQueueEventPublisher).publish(any(WaitingQueuePromotedEvent.class));
    }

    @Test
    @DisplayName("존재하지 않는 대기열을 승급하려 하면 예외가 발생하고 이벤트는 발행되지 않는다")
    void promoteWaitingQueue_WithNonExistentWaitingQueue_ShouldThrowExceptionAndNotPublish() {
        // given
        when(waitingQueueRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> waitingQueueService.promoteWaitingQueue(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_FOUND);

        verify(waitingQueueEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("이미 PROMOTED 상태인 대기열은 다시 승급할 수 없고 이벤트는 발행되지 않는다")
    void promoteWaitingQueue_WithAlreadyPromotedStatus_ShouldThrowExceptionAndNotPublish() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        waitingQueue.promote();
        when(waitingQueueRepository.findById(100L)).thenReturn(Optional.of(waitingQueue));

        // when & then
        assertThatThrownBy(() -> waitingQueueService.promoteWaitingQueue(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_PROMOTABLE);

        verify(waitingQueueEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("CANCELLED 상태인 대기열은 승급할 수 없고 이벤트는 발행되지 않는다")
    void promoteWaitingQueue_WithCancelledStatus_ShouldThrowExceptionAndNotPublish() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        waitingQueue.cancel();
        when(waitingQueueRepository.findById(100L)).thenReturn(Optional.of(waitingQueue));

        // when & then
        assertThatThrownBy(() -> waitingQueueService.promoteWaitingQueue(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.WAITING_QUEUE_NOT_PROMOTABLE);

        verify(waitingQueueEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("Redis 이벤트 발행이 실패해도 WaitingQueue 상태 변경 자체는 유지된다")
    void promoteWaitingQueue_WithEventPublishFailure_ShouldStillPersistStatusChange() {
        // given
        Resource resource = activeResource();
        WaitingQueue waitingQueue = waitingQueue(100L, user(), resource);
        when(waitingQueueRepository.findById(100L)).thenReturn(Optional.of(waitingQueue));
        doThrow(new RuntimeException("Redis publish 실패"))
                .when(waitingQueueEventPublisher).publish(any(WaitingQueuePromotedEvent.class));

        // when
        waitingQueueService.promoteWaitingQueue(100L);

        // then
        assertThat(waitingQueue.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
        verify(waitingQueueEventPublisher).publish(any(WaitingQueuePromotedEvent.class));
    }
}
