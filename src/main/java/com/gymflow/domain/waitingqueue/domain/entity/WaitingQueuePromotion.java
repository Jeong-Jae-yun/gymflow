package com.gymflow.domain.waitingqueue.domain.entity;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "waiting_queue_promotions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_waiting_queue_promotion_waiting_queue",
                        columnNames = "waiting_queue_id"
                ),
                @UniqueConstraint(
                        name = "uk_waiting_queue_promotion_reservation",
                        columnNames = "reservation_id"
                )
        },
        indexes = {
                @Index(name = "idx_promotion_resource_start_end_status", columnList = "resource_id,start_at,end_at,status"),
                @Index(name = "idx_promotion_user_status", columnList = "user_id,status")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitingQueuePromotion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "waiting_queue_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_promotion_waiting_queue")
    )
    private WaitingQueue waitingQueue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_promotion_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "resource_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_promotion_resource")
    )
    private Resource resource;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PromotionStatus status;

    @Column(name = "offered_at", nullable = false)
    private LocalDateTime offeredAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "reservation_id",
            foreignKey = @ForeignKey(name = "fk_promotion_reservation")
    )
    private Reservation reservation;

    @Builder
    public WaitingQueuePromotion(
            WaitingQueue waitingQueue, User user, Resource resource,
            LocalDateTime startAt, LocalDateTime endAt,
            LocalDateTime offeredAt, LocalDateTime expiresAt) {
        validateWaitingQueue(waitingQueue);
        validateUser(user);
        validateResource(resource);
        validateTimeRange(startAt, endAt);
        validateOfferWindow(offeredAt, expiresAt);

        this.waitingQueue = waitingQueue;
        this.user = user;
        this.resource = resource;
        this.startAt = startAt;
        this.endAt = endAt;
        this.offeredAt = offeredAt;
        this.expiresAt = expiresAt;
        this.status = PromotionStatus.OFFERED;
    }

    private static void validateWaitingQueue(WaitingQueue waitingQueue) {
        if (waitingQueue == null) {
            throw new IllegalArgumentException("WaitingQueuePromotion은 WaitingQueue 없이 생성할 수 없습니다.");
        }
    }

    private static void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("WaitingQueuePromotion은 User 없이 생성할 수 없습니다.");
        }
    }

    private static void validateResource(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("WaitingQueuePromotion은 Resource 없이 생성할 수 없습니다.");
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

    private static void validateOfferWindow(LocalDateTime offeredAt, LocalDateTime expiresAt) {
        if (offeredAt == null) {
            throw new IllegalArgumentException("offeredAt은 필수입니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt은 필수입니다.");
        }
        if (!offeredAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("offeredAt은 expiresAt보다 이전이어야 합니다.");
        }
    }

    public void accept(LocalDateTime now) {
        if (status != PromotionStatus.OFFERED) {
            throw new IllegalStateException("OFFERED 상태의 Promotion만 수락할 수 있습니다.");
        }

        this.status = PromotionStatus.ACCEPTED;
        this.acceptedAt = now;
    }

    public void linkReservation(Reservation reservation) {
        if (status != PromotionStatus.ACCEPTED) {
            throw new IllegalStateException("ACCEPTED 상태의 Promotion만 Reservation과 연결할 수 있습니다.");
        }
        if (reservation == null) {
            throw new IllegalArgumentException("연결할 Reservation은 필수입니다.");
        }
        if (this.reservation != null) {
            throw new IllegalStateException("이미 Reservation과 연결된 Promotion입니다.");
        }

        this.reservation = reservation;
    }

    public void reject(LocalDateTime now) {
        if (status != PromotionStatus.OFFERED) {
            throw new IllegalStateException("OFFERED 상태의 Promotion만 거절할 수 있습니다.");
        }

        this.status = PromotionStatus.REJECTED;
        this.rejectedAt = now;
    }

    public void expire(LocalDateTime now) {
        if (status != PromotionStatus.OFFERED) {
            throw new IllegalStateException("OFFERED 상태의 Promotion만 만료 처리할 수 있습니다.");
        }

        this.status = PromotionStatus.EXPIRED;
        this.expiredAt = now;
    }
}
