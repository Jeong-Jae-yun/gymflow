package com.gymflow.domain.reservation.domain.entity;

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
}
