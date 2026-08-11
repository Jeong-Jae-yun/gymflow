package com.gymflow.domain.reservation.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
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
}
