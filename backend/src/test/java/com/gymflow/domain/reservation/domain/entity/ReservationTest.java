package com.gymflow.domain.reservation.domain.entity;

import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private User createUser() {
        return User.builder()
                .email("test@gymflow.com")
                .password("securePassword123")
                .name("John Doe")
                .build();
    }

    private Resource createResource() {
        return Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
    }

    @Test
    @DisplayName("Reservation은 필수 값으로 정상 생성된다")
    void createReservation_WithRequiredFields_ShouldSucceed() {
        // given
        UUID batchId = UUID.randomUUID();
        User user = createUser();
        Resource resource = createResource();
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 12, 10, 15);

        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();

        // then
        assertThat(reservation.getReservationBatchId()).isEqualTo(batchId);
        assertThat(reservation.getUser()).isSameAs(user);
        assertThat(reservation.getResource()).isSameAs(resource);
        assertThat(reservation.getStartAt()).isEqualTo(startAt);
        assertThat(reservation.getEndAt()).isEqualTo(endAt);
    }

    @Test
    @DisplayName("Reservation 생성 시 status는 항상 CONFIRMED이다")
    void createReservation_ShouldHaveConfirmedStatus() {
        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("reservationBatchId는 UUID 타입으로 정상 저장된다")
    void createReservation_ShouldStoreReservationBatchIdAsUuid() {
        // given
        UUID batchId = UUID.randomUUID();

        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(batchId)
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        // then
        assertThat(reservation.getReservationBatchId()).isInstanceOf(UUID.class);
        assertThat(reservation.getReservationBatchId()).isEqualTo(batchId);
    }

    @Test
    @DisplayName("Reservation은 전달받은 User와 Resource와 연결된다")
    void createReservation_ShouldLinkGivenUserAndResource() {
        // given
        User user = createUser();
        Resource resource = createResource();

        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        // then
        assertThat(reservation.getUser()).isSameAs(user);
        assertThat(reservation.getResource()).isSameAs(resource);
    }

    @Test
    @DisplayName("user가 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullUser_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(null)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User");
    }

    @Test
    @DisplayName("resource가 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullResource_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(null)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource");
    }

    @Test
    @DisplayName("reservationBatchId가 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullReservationBatchId_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(null)
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationBatchId");
    }

    @Test
    @DisplayName("startAt이 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullStartAt_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(null)
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startAt");
    }

    @Test
    @DisplayName("endAt이 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullEndAt_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endAt");
    }

    @Test
    @DisplayName("startAt이 endAt과 같으면 Reservation 생성에 실패한다")
    void createReservation_WithStartAtEqualToEndAt_ShouldThrowException() {
        // given
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 12, 10, 0);

        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(sameTime)
                .endAt(sameTime)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startAt");
    }

    @Test
    @DisplayName("startAt이 endAt보다 이후이면 Reservation 생성에 실패한다")
    void createReservation_WithStartAtAfterEndAt_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 30))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startAt");
    }

    @Test
    @DisplayName("동일한 reservationBatchId를 가진 여러 Reservation을 생성할 수 있다")
    void createMultipleReservations_WithSameBatchId_ShouldShareBatchId() {
        // given
        UUID batchId = UUID.randomUUID();
        User user = createUser();

        // when
        Reservation first = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        Reservation second = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 11, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 11, 15))
                .build();

        // then
        assertThat(first.getReservationBatchId()).isEqualTo(batchId);
        assertThat(second.getReservationBatchId()).isEqualTo(batchId);
    }

    private Reservation confirmedReservation() {
        return Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();
    }

    @Test
    @DisplayName("CONFIRMED 상태의 Reservation은 취소하면 CANCELLED 상태가 되고 cancelReason이 저장된다")
    void cancel_WithConfirmedReservation_ShouldChangeStatusAndStoreCancelReason() {
        // given
        Reservation reservation = confirmedReservation();

        // when
        reservation.cancel(CancelReason.SCHEDULE_CHANGE);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelReason()).isEqualTo(CancelReason.SCHEDULE_CHANGE);
    }

    @Test
    @DisplayName("취소되지 않은 Reservation의 cancelReason은 null이다")
    void cancelReason_WhenNotCancelled_ShouldBeNull() {
        // given
        Reservation reservation = confirmedReservation();

        // then
        assertThat(reservation.getCancelReason()).isNull();
    }

    @Test
    @DisplayName("cancelReason이 null이면 취소에 실패한다")
    void cancel_WithNullCancelReason_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();

        // when & then
        assertThatThrownBy(() -> reservation.cancel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("취소 사유");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("CONFIRMED가 아닌 상태의 Reservation은 취소할 수 없다")
    void cancel_WithNonConfirmedStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", status);

        // when & then
        assertThatThrownBy(() -> reservation.cancel(CancelReason.OTHER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(status);
        assertThat(reservation.getCancelReason()).isNull();
    }

    @Test
    @DisplayName("신규 생성된 Reservation의 extensionCount는 0이다")
    void extensionCount_WhenCreated_ShouldBeZero() {
        // given
        Reservation reservation = confirmedReservation();

        // then
        assertThat(reservation.getExtensionCount()).isZero();
    }

    @Test
    @DisplayName("연장할 때마다 extensionCount가 1씩 증가한다")
    void extend_EachCall_ShouldIncrementExtensionCountByOne() {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);

        // when
        reservation.extend(reservation.getEndAt().plusMinutes(15));
        reservation.extend(reservation.getEndAt().plusMinutes(15));

        // then
        assertThat(reservation.getExtensionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("2회까지는 연장할 수 있다")
    void extend_UpToTwice_ShouldSucceed() {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);

        // when & then
        reservation.extend(reservation.getEndAt().plusMinutes(10));
        reservation.extend(reservation.getEndAt().plusMinutes(10));

        assertThat(reservation.getExtensionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("3번째 연장은 실패한다")
    void extend_ThirdTime_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        reservation.extend(reservation.getEndAt().plusMinutes(10));
        reservation.extend(reservation.getEndAt().plusMinutes(10));

        // when & then
        assertThatThrownBy(() -> reservation.extend(reservation.getEndAt().plusMinutes(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getExtensionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("CHECKED_IN 상태의 Reservation은 정상적으로 연장된다")
    void extend_WithCheckedInReservation_ShouldSucceed() {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        LocalDateTime newEndAt = reservation.getEndAt().plusMinutes(15);

        // when
        reservation.extend(newEndAt);

        // then
        assertThat(reservation.getEndAt()).isEqualTo(newEndAt);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("CHECKED_IN 상태에서 연장하면 extensionCount가 증가한다")
    void extend_WithCheckedInReservation_ShouldIncrementExtensionCount() {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);

        // when
        reservation.extend(reservation.getEndAt().plusMinutes(15));

        // then
        assertThat(reservation.getExtensionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CONFIRMED", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("CHECKED_IN이 아닌 상태의 Reservation은 연장할 수 없다")
    void extend_WithNonExtendableStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime originalEndAt = reservation.getEndAt();
        ReflectionTestUtils.setField(reservation, "status", status);

        // when & then
        assertThatThrownBy(() -> reservation.extend(originalEndAt.plusMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getEndAt()).isEqualTo(originalEndAt);
        assertThat(reservation.getExtensionCount()).isZero();
    }

    @Test
    @DisplayName("새로운 endAt이 기존 endAt보다 이전이면 연장에 실패한다")
    void extend_WithNewEndAtBeforeCurrentEndAt_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        LocalDateTime originalEndAt = reservation.getEndAt();

        // when & then
        assertThatThrownBy(() -> reservation.extend(originalEndAt.minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reservation.getEndAt()).isEqualTo(originalEndAt);
        assertThat(reservation.getExtensionCount()).isZero();
    }

    @Test
    @DisplayName("새로운 endAt이 기존 endAt과 같으면 연장에 실패한다")
    void extend_WithNewEndAtEqualToCurrentEndAt_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.CHECKED_IN);
        LocalDateTime originalEndAt = reservation.getEndAt();

        // when & then
        assertThatThrownBy(() -> reservation.extend(originalEndAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reservation.getEndAt()).isEqualTo(originalEndAt);
        assertThat(reservation.getExtensionCount()).isZero();
    }

    @Test
    @DisplayName("startAt 이내에 체크인하면 CHECKED_IN 상태가 된다")
    void checkIn_WithConfirmedReservation_ShouldChangeStatusToCheckedIn() {
        // given
        Reservation reservation = confirmedReservation();

        // when
        reservation.checkIn(reservation.getStartAt());

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("체크인하면 now 시각이 checkInAt으로 저장된다")
    void checkIn_ShouldStoreCheckInAt() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime now = reservation.getStartAt();

        // when
        reservation.checkIn(now);

        // then
        assertThat(reservation.getCheckInAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("CHECKED_IN 상태의 Reservation은 체크아웃하면 COMPLETED 상태가 된다")
    void checkOut_WithCheckedInReservation_ShouldChangeStatusToCompleted() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.checkIn(reservation.getStartAt());

        // when
        reservation.checkOut();

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
    }

    @Test
    @DisplayName("체크아웃하면 checkOutAt이 저장된다")
    void checkOut_ShouldStoreCheckOutAt() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.checkIn(reservation.getStartAt());
        LocalDateTime before = LocalDateTime.now();

        // when
        reservation.checkOut();

        // then
        LocalDateTime after = LocalDateTime.now();
        assertThat(reservation.getCheckOutAt()).isNotNull();
        assertThat(reservation.getCheckOutAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("checkInAt은 checkOutAt보다 이후가 되지 않는다")
    void checkInAt_ShouldNotBeAfterCheckOutAt() {
        // given
        Reservation reservation = confirmedReservation();

        // when
        reservation.checkIn(reservation.getStartAt());
        reservation.checkOut();

        // then
        assertThat(reservation.getCheckInAt()).isBeforeOrEqualTo(reservation.getCheckOutAt());
    }

    @Test
    @DisplayName("이미 CHECKED_IN 상태인 Reservation은 다시 체크인할 수 없다")
    void checkIn_WithAlreadyCheckedInReservation_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.checkIn(reservation.getStartAt());

        // when & then
        assertThatThrownBy(() -> reservation.checkIn(reservation.getStartAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("CONFIRMED 상태의 Reservation은 체크아웃할 수 없다")
    void checkOut_WithConfirmedReservation_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();

        // when & then
        assertThatThrownBy(reservation::checkOut)
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("COMPLETED 상태의 Reservation은 다시 체크아웃할 수 없다")
    void checkOut_WithCompletedReservation_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.checkIn(reservation.getStartAt());
        reservation.checkOut();

        // when & then
        assertThatThrownBy(reservation::checkOut)
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("CONFIRMED가 아닌 상태의 Reservation은 체크인할 수 없다")
    void checkIn_WithNonConfirmedStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", status);

        // when & then
        assertThatThrownBy(() -> reservation.checkIn(reservation.getStartAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("startAt - 5분보다 이전에는 체크인할 수 없다")
    void checkIn_BeforeCheckInWindow_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime beforeWindow = reservation.getStartAt()
                .minusMinutes(Reservation.CHECK_IN_WINDOW_MINUTES)
                .minusSeconds(1);

        // when & then
        assertThatThrownBy(() -> reservation.checkIn(beforeWindow))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("startAt - 5분 시점에는 체크인할 수 있다")
    void checkIn_AtCheckInWindowLowerBoundary_ShouldSucceed() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime atLowerBoundary = reservation.getStartAt().minusMinutes(Reservation.CHECK_IN_WINDOW_MINUTES);

        // when
        reservation.checkIn(atLowerBoundary);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("startAt 이후에는 체크인할 수 없다")
    void checkIn_AfterStartAt_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime afterStartAt = reservation.getStartAt().plusSeconds(1);

        // when & then
        assertThatThrownBy(() -> reservation.checkIn(afterStartAt))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("CONFIRMED 상태에서 startAt 이전에는 NO_SHOW 처리할 수 없다")
    void noShow_BeforeStartAt_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime beforeStartAt = reservation.getStartAt().minusSeconds(1);

        // when & then
        assertThatThrownBy(() -> reservation.noShow(beforeStartAt))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("startAt 시점에는 NO_SHOW 처리할 수 있다")
    void noShow_AtStartAt_ShouldSucceed() {
        // given
        Reservation reservation = confirmedReservation();

        // when
        reservation.noShow(reservation.getStartAt());

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
    }

    @Test
    @DisplayName("startAt 이후에도 NO_SHOW 처리할 수 있다")
    void noShow_AfterStartAt_ShouldSucceed() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime afterStartAt = reservation.getStartAt().plusMinutes(10);

        // when
        reservation.noShow(afterStartAt);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
    }

    @Test
    @DisplayName("CHECKED_IN 상태에서는 NO_SHOW 처리할 수 없다")
    void noShow_WithCheckedInReservation_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.checkIn(reservation.getStartAt());

        // when & then
        assertThatThrownBy(() -> reservation.noShow(reservation.getStartAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CANCELLED", "COMPLETED"})
    @DisplayName("CANCELLED/COMPLETED 상태에서는 NO_SHOW 처리할 수 없다")
    void noShow_WithNonConfirmedStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", status);

        // when & then
        assertThatThrownBy(() -> reservation.noShow(reservation.getStartAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("Promotion 체크인: CONFIRMED 상태에서 deadline 이내면 CHECKED_IN이 된다")
    void checkInByPromotion_WithinDeadline_ShouldChangeStatusToCheckedIn() {
        // given: startAt이 이미 지난 뒤(Promotion ACCEPT가 startAt 이후 발생할 수 있음)에도 성공해야 한다
        Reservation reservation = confirmedReservation();
        LocalDateTime acceptedAt = reservation.getStartAt().plusMinutes(1);
        LocalDateTime deadline = acceptedAt.plusMinutes(1);
        LocalDateTime now = acceptedAt.plusSeconds(30);

        // when
        reservation.checkInByPromotion(now, deadline);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(reservation.getCheckInAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Promotion 체크인: deadline 시각과 정확히 같으면 체크인에 성공한다")
    void checkInByPromotion_AtDeadlineBoundary_ShouldSucceed() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime deadline = reservation.getStartAt().plusMinutes(2);

        // when
        reservation.checkInByPromotion(deadline, deadline);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("Promotion 체크인: deadline을 넘기면 체크인에 실패한다")
    void checkInByPromotion_AfterDeadline_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        LocalDateTime deadline = reservation.getStartAt().plusMinutes(2);
        LocalDateTime afterDeadline = deadline.plusSeconds(1);

        // when & then
        assertThatThrownBy(() -> reservation.checkInByPromotion(afterDeadline, deadline))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("Promotion 체크인: CONFIRMED가 아닌 상태는 체크인할 수 없다")
    void checkInByPromotion_WithNonConfirmedStatus_ShouldThrowException(ReservationStatus status) {
        // given
        Reservation reservation = confirmedReservation();
        ReflectionTestUtils.setField(reservation, "status", status);
        LocalDateTime deadline = reservation.getStartAt().plusMinutes(2);

        // when & then
        assertThatThrownBy(() -> reservation.checkInByPromotion(deadline, deadline))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("Promotion 체크인: 일반 체크인 시간창(startAt-5분~startAt)을 벗어나도 deadline 이내면 성공한다")
    void checkInByPromotion_OutsideNormalCheckInWindow_ShouldStillSucceed() {
        // given: 일반 checkIn()이라면 실패했을 startAt 이후 시각이지만, Promotion 체크인 정책에서는 성공해야 한다
        Reservation reservation = confirmedReservation();
        LocalDateTime now = reservation.getStartAt().plusSeconds(1);
        LocalDateTime deadline = reservation.getStartAt().plusMinutes(1);

        // when
        reservation.checkInByPromotion(now, deadline);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("NO_SHOW 처리 이후에는 체크인할 수 없다")
    void checkIn_WithNoShowReservation_ShouldThrowException() {
        // given
        Reservation reservation = confirmedReservation();
        reservation.noShow(reservation.getStartAt());

        // when & then
        assertThatThrownBy(() -> reservation.checkIn(reservation.getStartAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
    }

}
