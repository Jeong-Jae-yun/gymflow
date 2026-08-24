package com.gymflow.domain.reservation.domain.entity;

import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {

    public static final int MAX_EXTENSION_COUNT = 2;
    public static final int CHECK_IN_WINDOW_MINUTES = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reservation_batch_id", nullable = false, columnDefinition = "CHAR(36)")
    private UUID reservationBatchId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_reservation_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "resource_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_reservation_resource")
    )
    private Resource resource;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @Column(name = "extension_count", nullable = false)
    private Integer extensionCount;

    @Column(name = "check_in_at")
    private LocalDateTime checkInAt;

    @Column(name = "check_out_at")
    private LocalDateTime checkOutAt;

    @Builder
    public Reservation(UUID reservationBatchId, User user, Resource resource, LocalDateTime startAt, LocalDateTime endAt) {
        validateUser(user);
        validateResource(resource);
        validateReservationBatchId(reservationBatchId);
        validateTimeRange(startAt, endAt);

        this.reservationBatchId = reservationBatchId;
        this.user = user;
        this.resource = resource;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = ReservationStatus.CONFIRMED;
        this.extensionCount = 0;
    }

    private static void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("Reservation은 User 없이 생성할 수 없습니다.");
        }
    }

    private static void validateResource(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Reservation은 Resource 없이 생성할 수 없습니다.");
        }
    }

    private static void validateReservationBatchId(UUID reservationBatchId) {
        if (reservationBatchId == null) {
            throw new IllegalArgumentException("reservationBatchId는 필수입니다.");
        }
    }

    private static void validateTimeRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null) {
            throw new IllegalArgumentException("startAt은 필수입니다.");
        }
        if (endAt == null) {
            throw new IllegalArgumentException("endAt은 필수입니다.");
        }
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("startAt은 endAt보다 이전이어야 합니다.");
        }
    }

    public void cancel(CancelReason cancelReason) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED 상태의 예약만 취소할 수 있습니다.");
        }
        if (cancelReason == null) {
            throw new IllegalArgumentException("취소 사유는 필수입니다.");
        }

        this.status = ReservationStatus.CANCELLED;
        this.cancelReason = cancelReason;
    }

    public void extend(LocalDateTime newEndAt) {
        if (status != ReservationStatus.CHECKED_IN) {
            throw new IllegalStateException("CHECKED_IN 상태의 예약만 연장할 수 있습니다.");
        }
        if (extensionCount >= MAX_EXTENSION_COUNT) {
            throw new IllegalStateException("최대 연장 횟수를 초과했습니다.");
        }
        if (newEndAt == null || !newEndAt.isAfter(endAt)) {
            throw new IllegalArgumentException("새로운 endAt은 기존 endAt보다 이후여야 합니다.");
        }

        this.endAt = newEndAt;
        this.extensionCount++;
    }

    public void checkIn(LocalDateTime now) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED 상태의 예약만 체크인할 수 있습니다.");
        }
        if (now.isBefore(startAt.minusMinutes(CHECK_IN_WINDOW_MINUTES))) {
            throw new IllegalStateException("체크인 가능 시간이 아직 되지 않았습니다.");
        }
        if (now.isAfter(startAt)) {
            throw new IllegalStateException("체크인 가능 시간이 지났습니다.");
        }

        this.status = ReservationStatus.CHECKED_IN;
        this.checkInAt = now;
    }

    public void checkInByPromotion(LocalDateTime now, LocalDateTime deadline) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED 상태의 예약만 체크인할 수 있습니다.");
        }
        if (now.isAfter(deadline)) {
            throw new IllegalStateException("Promotion 체크인 가능 시간이 지났습니다.");
        }

        this.status = ReservationStatus.CHECKED_IN;
        this.checkInAt = now;
    }

    public void checkOut() {
        if (status != ReservationStatus.CHECKED_IN) {
            throw new IllegalStateException("CHECKED_IN 상태의 예약만 체크아웃할 수 있습니다.");
        }

        this.status = ReservationStatus.COMPLETED;
        this.checkOutAt = LocalDateTime.now();
    }

    public void noShow(LocalDateTime now) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED 상태의 예약만 노쇼 처리할 수 있습니다.");
        }
        if (now.isBefore(startAt)) {
            throw new IllegalStateException("체크인 허용 시간이 아직 지나지 않았습니다.");
        }

        this.status = ReservationStatus.NO_SHOW;
    }
}
