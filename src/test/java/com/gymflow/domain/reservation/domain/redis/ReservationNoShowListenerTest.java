package com.gymflow.domain.reservation.domain.redis;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.service.ReservationService;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.usagehistory.domain.repository.UsageHistoryRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.service.PromotionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis expired 이벤트가 ReservationService.handleNoShowExpiration(...)까지 올바르게
 * 연결되는지 검증한다. 실제 Pub/Sub 배선(RedisMessageListenerContainer 등록)은
 * Spring 생명주기(afterPropertiesSet)에 위임하므로 여기서는 onMessage(...) 자체의
 * 메시지 파싱과 위임 로직만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationNoShowListenerTest {

    private static final Long RESERVATION_ID = 100L;
    private static final String EXPIRED_CHANNEL = "__keyevent@0__:expired";

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UsageHistoryRepository usageHistoryRepository;

    @Mock
    private ReservationSlotLockRepository reservationSlotLockRepository;

    @Mock
    private ReservationNoShowRepository reservationNoShowRepository;

    @Mock
    private ResourceRankingRedisRepository resourceRankingRedisRepository;

    @Mock
    private PromotionService promotionService;

    private ReservationNoShowListener listener;

    @BeforeEach
    void setUp() {
        ReservationService reservationService = new ReservationService(
                reservationRepository, resourceRepository, userRepository,
                usageHistoryRepository, reservationSlotLockRepository, reservationNoShowRepository,
                resourceRankingRedisRepository, promotionService);
        listener = new ReservationNoShowListener(new RedisMessageListenerContainer(), reservationService);
    }

    private Reservation reservation(ReservationStatus status) {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        User user = User.builder()
                .email("test@gymflow.com")
                .password("securePassword123")
                .name("Tester")
                .build();
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(startAt.plusMinutes(15))
                .build();
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        ReflectionTestUtils.setField(reservation, "status", status);
        return reservation;
    }

    private Message expiredMessage(String expiredKey) {
        return new DefaultMessage(
                EXPIRED_CHANNEL.getBytes(StandardCharsets.UTF_8), expiredKey.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CONFIRMED 상태의 Reservation은 TTL 만료 이벤트로 NO_SHOW 처리된다")
    void onMessage_WithConfirmedReservation_ShouldBecomeNoShow() {
        // given
        Reservation reservation = reservation(ReservationStatus.CONFIRMED);
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));

        // when
        listener.onMessage(expiredMessage(ReservationNoShowKey.from(RESERVATION_ID)), null);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("CONFIRMED가 아닌 상태의 Reservation은 TTL 만료 이벤트가 와도 상태가 바뀌지 않는다")
    void onMessage_WithNonConfirmedReservation_ShouldNotChangeStatus(ReservationStatus status) {
        // given
        Reservation reservation = reservation(status);
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));

        // when
        listener.onMessage(expiredMessage(ReservationNoShowKey.from(RESERVATION_ID)), null);

        // then
        assertThat(reservation.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("존재하지 않는 Reservation에 대한 만료 이벤트는 예외 없이 안전하게 종료된다")
    void onMessage_WithNonExistentReservation_ShouldNotThrow() {
        // given
        when(reservationRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatCode(() -> listener.onMessage(expiredMessage(ReservationNoShowKey.from(999L)), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NO_SHOW Key 형식이 아닌 만료 이벤트는 무시하고 Reservation을 조회하지 않는다")
    void onMessage_WithUnrelatedExpiredKey_ShouldBeIgnored() {
        // when
        listener.onMessage(expiredMessage("gymflow:lock:reservation:101:2026-08-20T14:00:2026-08-20T14:15"), null);

        // then
        verify(reservationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("메시지 본문이 비어 있으면 아무 것도 하지 않는다")
    void onMessage_WithEmptyBody_ShouldBeIgnored() {
        // given
        Message emptyMessage = new DefaultMessage(EXPIRED_CHANNEL.getBytes(StandardCharsets.UTF_8), new byte[0]);

        // when
        listener.onMessage(emptyMessage, null);

        // then
        verify(reservationRepository, never()).findById(any());
    }

    @Test
    @DisplayName("handleNoShowExpiration에서 예외가 발생해도 Listener는 예외를 전파하지 않는다")
    void onMessage_WithServiceException_ShouldNotPropagate() {
        // given
        when(reservationRepository.findById(RESERVATION_ID)).thenThrow(new RuntimeException("DB 오류"));

        // when & then
        assertThatCode(() -> listener.onMessage(expiredMessage(ReservationNoShowKey.from(RESERVATION_ID)), null))
                .doesNotThrowAnyException();
    }
}
