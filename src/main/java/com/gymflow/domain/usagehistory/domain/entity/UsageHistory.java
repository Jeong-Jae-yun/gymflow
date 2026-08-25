package com.gymflow.domain.usagehistory.domain.entity;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "usage_histories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usage_history_reservation",
                columnNames = "reservation_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "reservation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_usage_history_reservation")
    )
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_usage_history_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "resource_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_usage_history_resource")
    )
    private Resource resource;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    @Column(name = "duration", nullable = false)
    private Integer duration;

    @Builder
    public UsageHistory(Reservation reservation, User user, Resource resource,
                         LocalDateTime startedAt, LocalDateTime endedAt) {
        validateReservation(reservation);
        validateUser(user);
        validateResource(resource);
        validateTimeRange(startedAt, endedAt);

        this.reservation = reservation;
        this.user = user;
        this.resource = resource;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.duration = (int) Duration.between(startedAt, endedAt).toMinutes();
    }

    private static void validateReservation(Reservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("UsageHistory는 Reservation 없이 생성할 수 없습니다.");
        }
    }

    private static void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("UsageHistory는 User 없이 생성할 수 없습니다.");
        }
    }

    private static void validateResource(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("UsageHistory는 Resource 없이 생성할 수 없습니다.");
        }
    }

    private static void validateTimeRange(LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt은 필수입니다.");
        }
        if (endedAt == null) {
            throw new IllegalArgumentException("endedAt은 필수입니다.");
        }
        if (!startedAt.isBefore(endedAt)) {
            throw new IllegalArgumentException("startedAt은 endedAt보다 이전이어야 합니다.");
        }
    }
}
