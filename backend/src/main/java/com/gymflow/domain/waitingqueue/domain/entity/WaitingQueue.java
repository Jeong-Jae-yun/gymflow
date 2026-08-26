package com.gymflow.domain.waitingqueue.domain.entity;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "waiting_queues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaitingQueue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_waiting_queue_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "resource_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_waiting_queue_resource")
    )
    private Resource resource;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WaitingQueueStatus status;

    @Builder
    public WaitingQueue(User user, Resource resource, LocalDateTime startAt, LocalDateTime endAt) {
        validateUser(user);
        validateResource(resource);
        validateTimeRange(startAt, endAt);

        this.user = user;
        this.resource = resource;
        this.startAt = startAt;
        this.endAt = endAt;
        this.status = WaitingQueueStatus.WAITING;
    }

    private static void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("WaitingQueue는 User 없이 생성할 수 없습니다.");
        }
    }

    private static void validateResource(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("WaitingQueue는 Resource 없이 생성할 수 없습니다.");
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

    public void cancel() {
        if (status != WaitingQueueStatus.WAITING) {
            throw new IllegalStateException("WAITING 상태의 대기열만 취소할 수 있습니다.");
        }

        this.status = WaitingQueueStatus.CANCELLED;
    }

    public void promote() {
        if (status != WaitingQueueStatus.WAITING) {
            throw new IllegalStateException("WAITING 상태의 대기열만 승급할 수 있습니다.");
        }

        this.status = WaitingQueueStatus.PROMOTED;
    }
}
