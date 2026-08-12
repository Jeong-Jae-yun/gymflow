package com.gymflow.domain.reservation.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.UsageHistoryRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Long CURRENT_USER_ID = 1L;
    private static final Long RESOURCE_ID = 10L;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UsageHistoryRepository usageHistoryRepository;

    @InjectMocks
    private ReservationService reservationService;

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

    private Resource activeResourceWithPolicy(int minDuration, int maxDuration) {
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
                .maxDuration(maxDuration)
                .build();

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

    private Reservation reservation(Long id, Resource resource, User user, LocalDateTime startAt, LocalDateTime endAt) {
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }

    private void stubSave() {
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            ReflectionTestUtils.setField(reservation, "id", 100L);
            return reservation;
        });
    }

    @Test
    @DisplayName("정상적인 요청이면 Reservation을 생성하고 ReservationResponse를 반환한다")
    void createReservation_WithValidRequest_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        ReservationResponse response = reservationService.createReservation(request);

        // then
        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(response.startAt()).isEqualTo(startAt);
        assertThat(response.endAt()).isEqualTo(startAt.plusMinutes(30));
    }

    @Test
    @DisplayName("Resource가 존재하지 않으면 예외가 발생한다")
    void createReservation_WithNonExistentResource_ShouldThrowException() {
        // given
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("Resource가 ACTIVE 상태가 아니면 예외가 발생한다")
    void createReservation_WithInactiveResource_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReflectionTestUtils.setField(resource, "status", ResourceStatus.MAINTENANCE);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_ACTIVE);

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("Resource에 ReservationPolicy가 없으면 예외가 발생한다")
    void createReservation_WithoutReservationPolicy_ShouldThrowException() {
        // given
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", RESOURCE_ID);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_POLICY_NOT_FOUND);

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("duration이 minDuration보다 작으면 예외가 발생한다")
    void createReservation_WithDurationBelowMin_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(30, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 15);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_DURATION);

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("duration이 maxDuration보다 크면 예외가 발생한다")
    void createReservation_WithDurationAboveMax_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 30);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 60);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_DURATION);

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("동일 Resource에 시간이 겹치는 CONFIRMED 예약이 있으면 예외가 발생한다")
    void createReservation_WithOverlappingReservation_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any()))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_TIME_CONFLICT);

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("예약 생성 시 reservationBatchId는 UUID로 생성된다")
    void createReservation_ShouldGenerateUuidReservationBatchId() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        reservationService.createReservation(request);

        // then
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().getReservationBatchId()).isInstanceOf(UUID.class);
    }

    @Test
    @DisplayName("생성된 Reservation의 초기 상태는 CONFIRMED이다")
    void createReservation_ShouldHaveConfirmedStatus() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        ReservationResponse response = reservationService.createReservation(request);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("현재 로그인한 사용자의 예약 목록을 Page 형태로 반환한다")
    void getMyReservations_ShouldReturnPageOfCurrentUsersReservations() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        Pageable pageable = PageRequest.of(0, 10);
        Page<Reservation> page = new PageImpl<>(List.of(reservation), pageable, 1);
        when(reservationRepository.findAllByUserId(CURRENT_USER_ID, pageable)).thenReturn(page);

        // when
        Page<ReservationResponse> response = reservationService.getMyReservations(pageable);

        // then
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).reservationId()).isEqualTo(100L);
        assertThat(response.getContent().get(0).resourceId()).isEqualTo(RESOURCE_ID);
    }

    @Test
    @DisplayName("예약 목록 조회 시 Pageable의 페이징 정보가 그대로 유지된다")
    void getMyReservations_ShouldPreservePagingInformation() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(101L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 9, 0), LocalDateTime.of(2026, 8, 12, 9, 30));
        Pageable pageable = PageRequest.of(1, 5);
        Page<Reservation> page = new PageImpl<>(List.of(reservation), pageable, 11);
        when(reservationRepository.findAllByUserId(CURRENT_USER_ID, pageable)).thenReturn(page);

        // when
        Page<ReservationResponse> response = reservationService.getMyReservations(pageable);

        // then
        assertThat(response.getNumber()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(5);
        assertThat(response.getTotalElements()).isEqualTo(11);
        assertThat(response.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("본인의 예약 상세를 정상적으로 조회한다")
    void getMyReservationDetail_WithOwnedReservation_ShouldReturnResponse() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        ReservationResponse response = reservationService.getMyReservationDetail(100L);

        // then
        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.resourceId()).isEqualTo(RESOURCE_ID);
    }

    @Test
    @DisplayName("존재하지 않는 예약을 조회하면 예외가 발생한다")
    void getMyReservationDetail_WithNonExistentReservation_ShouldThrowException() {
        // given
        when(reservationRepository.findByIdAndUserId(999L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.getMyReservationDetail(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 예약을 조회하면 예외가 발생한다")
    void getMyReservationDetail_WithOtherUsersReservation_ShouldThrowException() {
        // given
        // findByIdAndUserId가 이미 소유자 기준으로 필터링하므로 타인의 예약은 조회되지 않는다
        when(reservationRepository.findByIdAndUserId(200L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.getMyReservationDetail(200L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("CONFIRMED 상태의 예약은 정상적으로 취소된다")
    void cancelReservation_WithConfirmedReservation_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE);

        // when
        ReservationResponse response = reservationService.cancelReservation(100L, request);

        // then
        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.cancelReason()).isEqualTo(CancelReason.SCHEDULE_CHANGE);
    }

    @Test
    @DisplayName("취소 후 Reservation의 status는 CANCELLED로 변경된다")
    void cancelReservation_ShouldChangeStatusToCancelled() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        reservationService.cancelReservation(100L, new CancelReservationRequest(CancelReason.PERSONAL_REASON));

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("취소 후 cancelReason이 저장된다")
    void cancelReservation_ShouldStoreCancelReason() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        reservationService.cancelReservation(100L, new CancelReservationRequest(CancelReason.WRONG_RESERVATION));

        // then
        assertThat(reservation.getCancelReason()).isEqualTo(CancelReason.WRONG_RESERVATION);
    }

    @Test
    @DisplayName("존재하지 않는 예약을 취소하면 예외가 발생한다")
    void cancelReservation_WithNonExistentReservation_ShouldThrowException() {
        // given
        when(reservationRepository.findByIdAndUserId(999L, CURRENT_USER_ID)).thenReturn(Optional.empty());
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.OTHER);

        // when & then
        assertThatThrownBy(() -> reservationService.cancelReservation(999L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 예약을 취소하면 예외가 발생한다")
    void cancelReservation_WithOtherUsersReservation_ShouldThrowException() {
        // given
        // findByIdAndUserId가 이미 소유자 기준으로 필터링하므로 타인의 예약은 조회되지 않는다
        when(reservationRepository.findByIdAndUserId(200L, CURRENT_USER_ID)).thenReturn(Optional.empty());
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.OTHER);

        // when & then
        assertThatThrownBy(() -> reservationService.cancelReservation(200L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW", "EXPIRED"})
    @DisplayName("CONFIRMED가 아닌 상태의 예약은 취소할 수 없다")
    void cancelReservation_WithNonConfirmedStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.OTHER);

        // when & then
        assertThatThrownBy(() -> reservationService.cancelReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_CANCELLABLE);
    }

    @Test
    @DisplayName("cancelReason이 null이면 취소에 실패한다")
    void cancelReservation_WithNullCancelReason_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        CancelReservationRequest request = new CancelReservationRequest(null);

        // when & then
        assertThatThrownBy(() -> reservationService.cancelReservation(100L, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정상적인 연장 요청이면 endAt이 늘어나고 extensionCount가 증가한다")
    void extendReservation_WithValidRequest_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any(), eq(100L)))
                .thenReturn(false);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when
        ReservationResponse response = reservationService.extendReservation(100L, request);

        // then
        assertThat(response.endAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 30));
        assertThat(response.extensionCount()).isEqualTo(1);
        assertThat(reservation.getEndAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 30));
        assertThat(reservation.getExtensionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자의 예약을 연장하면 예외가 발생한다")
    void extendReservation_WithOtherUsersReservation_ShouldThrowException() {
        // given
        // findByIdAndUserId가 이미 소유자 기준으로 필터링하므로 타인의 예약은 조회되지 않는다
        when(reservationRepository.findByIdAndUserId(200L, CURRENT_USER_ID)).thenReturn(Optional.empty());
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(200L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("존재하지 않는 예약을 연장하면 예외가 발생한다")
    void extendReservation_WithNonExistentReservation_ShouldThrowException() {
        // given
        when(reservationRepository.findByIdAndUserId(999L, CURRENT_USER_ID)).thenReturn(Optional.empty());
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(999L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("CHECKED_IN 상태의 예약도 정상적으로 연장된다")
    void extendReservation_WithCheckedInReservation_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any(), eq(100L)))
                .thenReturn(false);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when
        ReservationResponse response = reservationService.extendReservation(100L, request);

        // then
        assertThat(response.endAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 30));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"COMPLETED", "CANCELLED", "NO_SHOW", "EXPIRED"})
    @DisplayName("CONFIRMED/CHECKED_IN이 아닌 상태의 예약은 연장할 수 없다")
    void extendReservation_WithNonExtendableStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_EXTENDABLE);
    }

    @Test
    @DisplayName("Resource가 ACTIVE 상태가 아니면 연장할 수 없다")
    void extendReservation_WithInactiveResource_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReflectionTestUtils.setField(resource, "status", ResourceStatus.MAINTENANCE);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_ACTIVE);
    }

    @Test
    @DisplayName("연장 후 총 이용시간이 maxDuration을 초과하면 예외가 발생한다")
    void extendReservation_WithTotalDurationExceedingMaxDuration_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 45));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        ReservationExtensionRequest request = new ReservationExtensionRequest(30);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_MAX_DURATION_EXCEEDED);
    }

    @Test
    @DisplayName("이미 2회 연장한 예약은 다시 연장할 수 없다")
    void extendReservation_WithExtensionCountAtLimit_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 15));
        ReflectionTestUtils.setField(reservation, "extensionCount", 2);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_EXTENSION_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("연장하려는 시간대가 다른 예약과 겹치면 예외가 발생한다")
    void extendReservation_WithOverlappingReservation_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any(), eq(100L)))
                .thenReturn(true);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_TIME_CONFLICT);
    }

    @Test
    @DisplayName("경계가 맞닿는 다른 예약이 있어도 정상적으로 연장된다")
    void extendReservation_WithAdjacentReservation_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.CONFIRMED), any(), any(), eq(100L)))
                .thenReturn(false);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when
        ReservationResponse response = reservationService.extendReservation(100L, request);

        // then
        assertThat(response.endAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 30));
    }

    @Test
    @DisplayName("CONFIRMED 상태의 예약은 정상적으로 체크인된다")
    void checkInReservation_WithConfirmedReservation_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        ReservationResponse response = reservationService.checkInReservation(100L);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("존재하지 않는 예약을 체크인하면 예외가 발생한다")
    void checkInReservation_WithNonExistentReservation_ShouldThrowException() {
        // given
        when(reservationRepository.findByIdAndUserId(999L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.checkInReservation(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 예약을 체크인하면 예외가 발생한다")
    void checkInReservation_WithOtherUsersReservation_ShouldThrowException() {
        // given
        // findByIdAndUserId가 이미 소유자 기준으로 필터링하므로 타인의 예약은 조회되지 않는다
        when(reservationRepository.findByIdAndUserId(200L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.checkInReservation(200L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW", "EXPIRED"})
    @DisplayName("CONFIRMED가 아닌 상태의 예약은 체크인할 수 없다")
    void checkInReservation_WithNonConfirmedStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.checkInReservation(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_CHECKINABLE);
    }

    @Test
    @DisplayName("CHECKED_IN 상태의 예약은 정상적으로 체크아웃된다")
    void checkOutReservation_WithCheckedInReservation_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        ReflectionTestUtils.setField(reservation, "checkInAt", LocalDateTime.now().minusMinutes(19));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        ReservationResponse response = reservationService.checkOutReservation(100L);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(reservation.getCheckOutAt()).isNotNull();
    }

    @Test
    @DisplayName("체크아웃 시 UsageHistory가 생성되고 reservation/user/resource가 올바르게 연결된다")
    void checkOutReservation_ShouldCreateUsageHistoryWithCorrectAssociations() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        User user = user();
        Reservation reservation = reservation(100L, resource, user,
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        ReflectionTestUtils.setField(reservation, "checkInAt", LocalDateTime.now().minusMinutes(19));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        reservationService.checkOutReservation(100L);

        // then
        ArgumentCaptor<UsageHistory> captor = ArgumentCaptor.forClass(UsageHistory.class);
        verify(usageHistoryRepository).save(captor.capture());
        UsageHistory usageHistory = captor.getValue();
        assertThat(usageHistory.getReservation()).isSameAs(reservation);
        assertThat(usageHistory.getUser()).isSameAs(user);
        assertThat(usageHistory.getResource()).isSameAs(resource);
        assertThat(usageHistory.getStartedAt()).isEqualTo(reservation.getCheckInAt());
        assertThat(usageHistory.getEndedAt()).isEqualTo(reservation.getCheckOutAt());
    }

    @Test
    @DisplayName("UsageHistory의 duration은 실제 체크인/체크아웃 시간 차이로 계산된다")
    void checkOutReservation_ShouldCalculateUsageHistoryDurationFromActualTime() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 30));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        ReflectionTestUtils.setField(reservation, "checkInAt", LocalDateTime.now().minusMinutes(19));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        reservationService.checkOutReservation(100L);

        // then
        ArgumentCaptor<UsageHistory> captor = ArgumentCaptor.forClass(UsageHistory.class);
        verify(usageHistoryRepository).save(captor.capture());
        long expectedDuration = java.time.Duration.between(
                reservation.getCheckInAt(), reservation.getCheckOutAt()).toMinutes();
        assertThat(captor.getValue().getDuration()).isEqualTo((int) expectedDuration);
    }

    @Test
    @DisplayName("존재하지 않는 예약을 체크아웃하면 예외가 발생한다")
    void checkOutReservation_WithNonExistentReservation_ShouldThrowException() {
        // given
        when(reservationRepository.findByIdAndUserId(999L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.checkOutReservation(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 예약을 체크아웃하면 예외가 발생한다")
    void checkOutReservation_WithOtherUsersReservation_ShouldThrowException() {
        // given
        // findByIdAndUserId가 이미 소유자 기준으로 필터링하므로 타인의 예약은 조회되지 않는다
        when(reservationRepository.findByIdAndUserId(200L, CURRENT_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.checkOutReservation(200L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CONFIRMED", "COMPLETED", "CANCELLED", "NO_SHOW", "EXPIRED"})
    @DisplayName("CHECKED_IN이 아닌 상태의 예약은 체크아웃할 수 없다")
    void checkOutReservation_WithNonCheckedInStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.checkOutReservation(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_CHECKOUTABLE);
        verify(usageHistoryRepository, never()).save(any(UsageHistory.class));
    }

    @Test
    @DisplayName("이미 체크아웃되어 COMPLETED된 예약을 다시 체크아웃하면 UsageHistory가 중복 생성되지 않는다")
    void checkOutReservation_WithAlreadyCompletedReservation_ShouldNotCreateDuplicateUsageHistory() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.COMPLETED);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.checkOutReservation(100L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_CHECKOUTABLE);
        verify(usageHistoryRepository, never()).save(any(UsageHistory.class));
    }
}
