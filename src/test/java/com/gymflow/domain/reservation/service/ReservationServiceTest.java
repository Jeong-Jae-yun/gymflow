package com.gymflow.domain.reservation.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationLockRepository;
import com.gymflow.domain.reservation.domain.redis.ReservationNoShowRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.UsageHistoryRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.service.PromotionService;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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

    @Mock
    private ReservationLockRepository reservationLockRepository;

    @Mock
    private ReservationNoShowRepository reservationNoShowRepository;

    @Mock
    private ResourceRankingRedisRepository resourceRankingRedisRepository;

    @Mock
    private PromotionService promotionService;

    @InjectMocks
    private ReservationService reservationService;

    @BeforeEach
    void setUpSecurityContext() {
        CustomUserDetails principal = new CustomUserDetails(CURRENT_USER_ID, "test@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // createReservation을 다루지 않는 테스트에서는 사용되지 않으므로 lenient로 등록한다
        lenient().when(reservationLockRepository.tryLock(any(), any(), any()))
                .thenReturn(Optional.of("lock-token"));
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
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
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
        verify(reservationLockRepository).tryLock(RESOURCE_ID, startAt, startAt.plusMinutes(30));
        verify(reservationLockRepository).unlock(RESOURCE_ID, startAt, startAt.plusMinutes(30), "lock-token");
    }

    @Test
    @DisplayName("동일 시간대에 활성 OFFERED Promotion이 있으면 일반 예약 생성이 차단된다")
    void createReservation_WithActiveOfferedPromotion_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(promotionService.hasActiveOffer(RESOURCE_ID, startAt, startAt.plusMinutes(30))).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_PROMOTION_RESERVED);

        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(reservationLockRepository).unlock(eq(RESOURCE_ID), any(), any(), eq("lock-token"));
    }

    @Test
    @DisplayName("활성 OFFERED Promotion이 없으면 일반 예약이 정상적으로 생성된다")
    void createReservation_WithoutActiveOfferedPromotion_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);

        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(promotionService.hasActiveOffer(RESOURCE_ID, startAt, startAt.plusMinutes(30))).thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        ReservationResponse response = reservationService.createReservation(request);

        // then
        assertThat(response.reservationId()).isEqualTo(100L);
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

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CHECKED_IN"})
    @DisplayName("동일 Resource에 시간이 겹치는 CONFIRMED 또는 CHECKED_IN(점유 상태) 예약이 있으면 예외가 발생한다")
    void createReservation_WithOverlappingOccupyingReservation_ShouldThrowException(ReservationStatus occupyingStatus) {
        // given: existsOverlapping은 CONFIRMED+CHECKED_IN(OCCUPYING_STATUSES)을 대상으로 점유 여부를 조회하므로,
        // 실제로 겹치는 예약이 occupyingStatus(CONFIRMED 또는 CHECKED_IN) 중 어느 쪽이든 동일하게 충돌로 처리되어야 한다
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_TIME_CONFLICT);

        verify(reservationRepository, never()).save(any(Reservation.class));
        // existsOverlapping이 실제 충돌을 판단했더라도 Lock은 finally에서 반드시 해제되어야 한다
        verify(reservationLockRepository).unlock(eq(RESOURCE_ID), any(), any(), eq("lock-token"));
    }

    @Test
    @DisplayName("점유 상태(CONFIRMED/CHECKED_IN)가 아닌 CANCELLED 예약과 시간이 겹쳐도 정상적으로 생성된다")
    void createReservation_WithOverlappingCancelledReservation_ShouldSucceed() {
        // given: existsOverlapping은 CANCELLED 등 비점유 상태를 대상에서 제외하므로 false를 반환한다
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        ReservationResponse response = reservationService.createReservation(request);

        // then
        assertThat(response.reservationId()).isEqualTo(100L);
        verify(reservationRepository).existsOverlapping(
                RESOURCE_ID, ReservationStatus.OCCUPYING_STATUSES, startAt, startAt.plusMinutes(30));
    }

    @Test
    @DisplayName("Lock 획득에 실패하면 RESERVATION_IN_PROGRESS 예외가 발생하고 Reservation이 생성되지 않는다")
    void createReservation_WithLockAcquisitionFailure_ShouldThrowReservationInProgress() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationLockRepository.tryLock(any(), any(), any())).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);

        verify(reservationRepository, never())
                .existsOverlapping(any(), anyCollection(), any(), any());
        verify(reservationRepository, never()).save(any(Reservation.class));
        // Lock을 획득하지 못했으므로 소유하지 않은 Lock을 해제하려 시도해서는 안 된다
        verify(reservationLockRepository, never()).unlock(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Reservation 저장 중 예외가 발생해도 finally에서 Lock이 해제된다")
    void createReservation_WithExceptionDuringSave_ShouldStillReleaseLock() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        when(reservationRepository.save(any(Reservation.class))).thenThrow(new RuntimeException("DB 오류"));

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(RuntimeException.class);

        verify(reservationLockRepository).unlock(eq(RESOURCE_ID), any(), any(), eq("lock-token"));
    }

    @Test
    @DisplayName("Reservation 생성 성공 후 NO_SHOW Redis Key가 등록된다")
    void createReservation_WithValidRequest_ShouldRegisterNoShowKey() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        reservationService.createReservation(request);

        // then
        verify(reservationNoShowRepository).register(eq(100L), any(Duration.class));
    }

    @Test
    @DisplayName("NO_SHOW Redis TTL은 startAt을 기준으로 계산된다")
    void createReservation_ShouldCalculateNoShowTtlFromStartAt() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        reservationService.createReservation(request);

        // then
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(reservationNoShowRepository).register(eq(100L), ttlCaptor.capture());
        Duration expectedTtl = Duration.between(LocalDateTime.now(), startAt);
        // 계산 시점(now())의 미세한 차이를 감안해 1초 오차까지 허용한다
        assertThat(ttlCaptor.getValue().minus(expectedTtl).abs()).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("NO_SHOW 가능 시점이 이미 지난 경우(TTL<=0) Redis Key를 등록하지 않는다")
    void createReservation_WithPastStartAt_ShouldNotRegisterNoShowKey() {
        // given: startAt이 과거이므로 startAt - now는 음수(TTL<=0)가 된다
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        ReservationResponse response = reservationService.createReservation(request);

        // then: Redis 등록 없이도 Reservation 생성 자체는 정상 성공한다
        assertThat(response.reservationId()).isEqualTo(100L);
        verify(reservationNoShowRepository, never()).register(any(), any());
    }

    @Test
    @DisplayName("NO_SHOW Redis 등록이 실패해도 Reservation 생성은 성공하고 Lock도 정상 해제된다")
    void createReservation_WithNoShowRegistrationFailure_ShouldStillSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        ReservationCreateRequest request = new ReservationCreateRequest(RESOURCE_ID, startAt, 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(reservationNoShowRepository).register(any(), any());

        // when
        ReservationResponse response = reservationService.createReservation(request);

        // then: Redis 장애와 무관하게 예약 생성은 성공하고, Lock 역시 정상적으로 해제된다
        // (Lock과 NO_SHOW Redis는 서로 다른 컴포넌트로 분리되어 있어 서로 영향을 주지 않는다)
        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(reservationLockRepository).unlock(eq(RESOURCE_ID), any(), any(), eq("lock-token"));
    }

    @Test
    @DisplayName("Reservation 저장이 성공하면 Resource Ranking score가 증가한다")
    void createReservation_WithValidRequest_ShouldIncrementResourceRanking() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        reservationService.createReservation(request);

        // then
        verify(resourceRankingRedisRepository).incrementReservationCount(RESOURCE_ID);
    }

    @Test
    @DisplayName("Resource Ranking 증가가 실패해도 Reservation 생성은 성공한다")
    void createReservation_WithRankingIncrementFailure_ShouldStillSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(resourceRankingRedisRepository).incrementReservationCount(RESOURCE_ID);

        // when
        ReservationResponse response = reservationService.createReservation(request);

        // then: Ranking Redis 장애와 무관하게 예약 생성은 성공한다
        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Reservation 저장이 실패하면 Resource Ranking은 증가하지 않는다")
    void createReservation_WithSaveFailure_ShouldNotIncrementResourceRanking() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        when(reservationRepository.save(any(Reservation.class))).thenThrow(new RuntimeException("DB 오류"));

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(RuntimeException.class);

        verify(resourceRankingRedisRepository, never()).incrementReservationCount(any());
    }

    @Test
    @DisplayName("Lock 획득에 실패하면 Reservation 저장과 Resource Ranking 증가 모두 일어나지 않는다")
    void createReservation_WithLockFailure_ShouldNotIncrementResourceRanking() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationLockRepository.tryLock(any(), any(), any())).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.createReservation(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);

        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(resourceRankingRedisRepository, never()).incrementReservationCount(any());
    }

    @Test
    @DisplayName("예약 생성 시 reservationBatchId는 UUID로 생성된다")
    void createReservation_ShouldGenerateUuidReservationBatchId() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReservationCreateRequest request =
                new ReservationCreateRequest(RESOURCE_ID, LocalDateTime.of(2026, 8, 12, 10, 0), 30);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
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
        when(reservationRepository.existsOverlapping(eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any()))
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
        verify(reservationNoShowRepository).remove(100L);
        verify(promotionService).tryPromote(RESOURCE_ID, reservation.getStartAt(), reservation.getEndAt());
    }

    @Test
    @DisplayName("WaitingQueue 자동 승급 처리 중 예외가 발생해도 예약 취소 자체는 성공한다")
    void cancelReservation_WithPromotionFailure_ShouldStillSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE);
        doThrow(new RuntimeException("승급 처리 오류"))
                .when(promotionService).tryPromote(any(), any(), any());

        // when
        ReservationResponse response = reservationService.cancelReservation(100L, request);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("NO_SHOW Redis Key 삭제가 실패해도 CANCEL 자체는 성공한다")
    void cancelReservation_WithNoShowKeyRemovalFailure_ShouldStillSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        doThrow(new RedisConnectionFailureException("연결 실패")).when(reservationNoShowRepository).remove(100L);
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE);

        // when
        ReservationResponse response = reservationService.cancelReservation(100L, request);

        // then: MySQL 상태 변경(source of truth)은 Redis 장애와 무관하게 성공한다
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
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
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
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
    @DisplayName("정상적인 연장 요청이면 Lock 획득→overlap 검사→extend→save→unlock 순서로 처리되고 endAt/extensionCount가 갱신된다")
    void extendReservation_WithValidRequest_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 14, 0);
        LocalDateTime originalEndAt = LocalDateTime.of(2026, 8, 12, 14, 15);
        LocalDateTime newEndAt = LocalDateTime.of(2026, 8, 12, 14, 30);
        Reservation reservation = reservation(100L, resource, user(), startAt, originalEndAt);
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any(), eq(100L)))
                .thenReturn(false);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when
        ReservationResponse response = reservationService.extendReservation(100L, request);

        // then
        assertThat(response.endAt()).isEqualTo(newEndAt);
        assertThat(response.extensionCount()).isEqualTo(1);
        assertThat(reservation.getEndAt()).isEqualTo(newEndAt);
        assertThat(reservation.getExtensionCount()).isEqualTo(1);
        // 기존 endAt(14:15)이 아니라 연장하려는 새로운 시간 구간(startAt~newEndAt)을 기준으로 Lock을 획득/해제해야 한다
        verify(reservationLockRepository).tryLock(RESOURCE_ID, startAt, newEndAt);
        verify(reservationRepository).existsOverlappingExcludingReservation(
                RESOURCE_ID, ReservationStatus.OCCUPYING_STATUSES, originalEndAt, newEndAt, 100L);
        verify(reservationRepository).save(reservation);
        verify(reservationLockRepository).unlock(RESOURCE_ID, startAt, newEndAt, "lock-token");
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

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CONFIRMED", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("CHECKED_IN이 아닌 상태의 예약은 연장할 수 없고 Lock을 획득하지 않는다")
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
        verify(reservationLockRepository, never()).tryLock(any(), any(), any());
        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    @DisplayName("Resource가 ACTIVE 상태가 아니면 연장할 수 없다")
    void extendReservation_WithInactiveResource_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        ReflectionTestUtils.setField(resource, "status", ResourceStatus.MAINTENANCE);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 30));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_ACTIVE);
    }

    @Test
    @DisplayName("연장 후 총 이용시간이 maxDuration을 초과하면 예외가 발생하고 Lock을 획득하지 않는다")
    void extendReservation_WithTotalDurationExceedingMaxDuration_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 45));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        ReservationExtensionRequest request = new ReservationExtensionRequest(30);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_MAX_DURATION_EXCEEDED);
        verify(reservationLockRepository, never()).tryLock(any(), any(), any());
    }

    @Test
    @DisplayName("이미 2회 연장한 예약은 다시 연장할 수 없고 Lock을 획득하지 않는다")
    void extendReservation_WithExtensionCountAtLimit_ShouldThrowException() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 10, 0), LocalDateTime.of(2026, 8, 12, 10, 15));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        ReflectionTestUtils.setField(reservation, "extensionCount", 2);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_EXTENSION_LIMIT_EXCEEDED);
        verify(reservationLockRepository, never()).tryLock(any(), any(), any());
    }

    @Test
    @DisplayName("Lock 획득에 실패하면 RESERVATION_IN_PROGRESS 예외가 발생하고 연장되지 않는다")
    void extendReservation_WithLockAcquisitionFailure_ShouldThrowReservationInProgress() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationLockRepository.tryLock(any(), any(), any())).thenReturn(Optional.empty());
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);

        verify(reservationRepository, never())
                .existsOverlappingExcludingReservation(any(), anyCollection(), any(), any(), any());
        verify(reservationRepository, never()).save(any(Reservation.class));
        assertThat(reservation.getEndAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 15));
        // Lock을 획득하지 못했으므로 소유하지 않은 Lock을 해제하려 시도해서는 안 된다
        verify(reservationLockRepository, never()).unlock(any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CHECKED_IN"})
    @DisplayName("연장하려는 시간대가 다른 CONFIRMED 또는 CHECKED_IN(점유 상태) 예약과 겹치면 예외가 발생하고 저장되지 않으며 Lock은 해제된다")
    void extendReservation_WithOverlappingOccupyingReservation_ShouldThrowException(ReservationStatus occupyingStatus) {
        // given: existsOverlappingExcludingReservation은 CONFIRMED+CHECKED_IN(OCCUPYING_STATUSES)을 대상으로
        // 자기 자신을 제외한 점유 여부를 조회하므로, 겹치는 다른 예약이 occupyingStatus 중 어느 쪽이든
        // 동일하게 충돌로 처리되어야 한다
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any(), eq(100L)))
                .thenReturn(true);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_TIME_CONFLICT);

        verify(reservationRepository, never()).save(any(Reservation.class));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(reservation.getEndAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 15));
        // overlap 재검증으로 충돌이 확인되었더라도 Lock은 finally에서 반드시 해제되어야 한다
        verify(reservationLockRepository).unlock(eq(RESOURCE_ID), any(), any(), eq("lock-token"));
    }

    @Test
    @DisplayName("경계가 맞닿는 다른 예약이 있어도 정상적으로 연장된다")
    void extendReservation_WithAdjacentReservation_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any(), eq(100L)))
                .thenReturn(false);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when
        ReservationResponse response = reservationService.extendReservation(100L, request);

        // then
        assertThat(response.endAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 30));
    }

    @Test
    @DisplayName("Reservation.extend()에서 예외가 발생해도 finally에서 Lock이 해제된다")
    void extendReservation_WithDomainExtendFailure_ShouldStillReleaseLock() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = spy(reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15)));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        doThrow(new IllegalStateException("도메인 불변식 위반")).when(reservation).extend(any());
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any(), eq(100L)))
                .thenReturn(false);
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(IllegalStateException.class);

        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(reservationLockRepository).unlock(eq(RESOURCE_ID), any(), any(), eq("lock-token"));
    }

    @Test
    @DisplayName("연장 저장 중 예외가 발생해도 finally에서 Lock이 해제된다")
    void extendReservation_WithExceptionDuringSave_ShouldStillReleaseLock() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        Reservation reservation = reservation(100L, resource, user(),
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(reservationRepository.existsOverlappingExcludingReservation(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any(), any(), eq(100L)))
                .thenReturn(false);
        when(reservationRepository.save(any(Reservation.class))).thenThrow(new RuntimeException("DB 오류"));
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);

        // when & then
        assertThatThrownBy(() -> reservationService.extendReservation(100L, request))
                .isInstanceOf(RuntimeException.class);

        verify(reservationLockRepository).unlock(eq(RESOURCE_ID), any(), any(), eq("lock-token"));
    }

    @Test
    @DisplayName("CONFIRMED 상태의 예약은 정상적으로 체크인된다")
    void checkInReservation_WithConfirmedReservation_ShouldSucceed() {
        // given: 체크인 가능 시간(startAt - 5분 ~ startAt) 내에서 체크인할 수 있도록 startAt을 현재 시각 근처로 둔다
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(2);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when
        ReservationResponse response = reservationService.checkInReservation(100L);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        verify(reservationNoShowRepository).remove(100L);
    }

    @Test
    @DisplayName("NO_SHOW Redis Key 삭제가 실패해도 CHECK_IN 자체는 성공한다")
    void checkInReservation_WithNoShowKeyRemovalFailure_ShouldStillSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(2);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        doThrow(new RedisConnectionFailureException("연결 실패")).when(reservationNoShowRepository).remove(100L);

        // when
        ReservationResponse response = reservationService.checkInReservation(100L);

        // then: MySQL 상태 변경(source of truth)은 Redis 장애와 무관하게 성공한다
        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("체크인 가능 시간이 아닌 예약은 체크인할 수 없다")
    void checkInReservation_OutsideCheckInWindow_ShouldThrowException() {
        // given: startAt이 이미 지나 체크인 허용 구간(startAt - 5분 ~ startAt)을 벗어난 상황
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.checkInReservation(100L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Promotion으로 생성된 예약은 promotionService가 체크인을 처리하고 일반 checkIn/NO_SHOW Key 삭제는 수행되지 않는다")
    void checkInReservation_WithPromotionReservation_ShouldDelegateToPromotionService() {
        // given: startAt이 이미 지나 일반 checkIn()이라면 실패할 시각이지만, Promotion 체크인 정책이 적용되어야 한다
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(promotionService.checkInPromotedReservation(eq(reservation), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    Reservation target = invocation.getArgument(0);
                    LocalDateTime now = invocation.getArgument(1);
                    ReflectionTestUtils.setField(target, "status", ReservationStatus.CHECKED_IN);
                    ReflectionTestUtils.setField(target, "checkInAt", now);
                    return true;
                });

        // when
        ReservationResponse response = reservationService.checkInReservation(100L);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_IN);
        verify(promotionService).checkInPromotedReservation(eq(reservation), any(LocalDateTime.class));
        verify(reservationNoShowRepository, never()).remove(any());
    }

    @Test
    @DisplayName("Promotion 체크인이 아니면(false 반환) 일반 checkIn 정책과 NO_SHOW Key 삭제가 수행된다")
    void checkInReservation_WithNonPromotionReservation_ShouldUseNormalCheckIn() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(2);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(30));
        when(reservationRepository.findByIdAndUserId(100L, CURRENT_USER_ID)).thenReturn(Optional.of(reservation));
        when(promotionService.checkInPromotedReservation(eq(reservation), any(LocalDateTime.class)))
                .thenReturn(false);

        // when
        ReservationResponse response = reservationService.checkInReservation(100L);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CHECKED_IN);
        verify(reservationNoShowRepository).remove(100L);
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
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
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
            names = {"CONFIRMED", "COMPLETED", "CANCELLED", "NO_SHOW"})
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

    @Test
    @DisplayName("체크인 허용 시간이 지난 CONFIRMED 예약은 NO_SHOW 처리된다")
    void noShow_WithEligibleReservation_ShouldSucceed() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(30));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(reservation));

        // when
        ReservationResponse response = reservationService.noShow(100L);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        verify(promotionService).tryPromote(RESOURCE_ID, reservation.getStartAt(), reservation.getEndAt());
    }

    @Test
    @DisplayName("존재하지 않는 예약을 NO_SHOW 처리하면 예외가 발생한다")
    void noShow_WithNonExistentReservation_ShouldThrowException() {
        // given
        when(reservationRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reservationService.noShow(999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("체크인 허용 시간이 지나지 않은 예약을 NO_SHOW 처리하면 예외가 발생한다")
    void noShow_BeforeGracePeriodElapsed_ShouldThrowException() {
        // given: startAt이 아직 도래하지 않았으므로 NO_SHOW 처리할 수 없다
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(30));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.noShow(100L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("handleNoShowExpiration: CONFIRMED 상태의 예약은 NO_SHOW로 전이된다")
    void handleNoShowExpiration_WithConfirmedReservation_ShouldBecomeNoShow() {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(15));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(reservation));

        // when
        reservationService.handleNoShowExpiration(100L);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        verify(promotionService).tryPromote(RESOURCE_ID, reservation.getStartAt(), reservation.getEndAt());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("handleNoShowExpiration: CONFIRMED가 아닌 예약은 상태가 바뀌지 않는다")
    void handleNoShowExpiration_WithNonConfirmedReservation_ShouldNotChangeStatus(ReservationStatus status) {
        // given
        Resource resource = activeResourceWithPolicy(15, 60);
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = reservation(100L, resource, user(), startAt, startAt.plusMinutes(15));
        ReflectionTestUtils.setField(reservation, "status", status);
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(reservation));

        // when
        reservationService.handleNoShowExpiration(100L);

        // then: 이미 CHECK_IN 등으로 전이된 경우(TTL 만료 전 처리했지만 Redis Key 삭제가
        // 실패했을 수 있는 상황 포함) noShow()를 호출하지 않고 상태를 그대로 유지한다
        assertThat(reservation.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("handleNoShowExpiration: 존재하지 않는 예약이면 예외 없이 안전하게 종료된다")
    void handleNoShowExpiration_WithNonExistentReservation_ShouldNotThrow() {
        // given
        when(reservationRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatCode(() -> reservationService.handleNoShowExpiration(999L))
                .doesNotThrowAnyException();
    }
}
