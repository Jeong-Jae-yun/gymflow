package com.gymflow.domain.waitingqueue.domain.repository;

import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WaitingQueuePromotionRepository extends JpaRepository<WaitingQueuePromotion, Long> {

    Optional<WaitingQueuePromotion> findByIdAndUserId(Long id, Long userId);

    boolean existsByWaitingQueueId(Long waitingQueueId);

    Optional<WaitingQueuePromotion> findByWaitingQueueId(Long waitingQueueId);

    Optional<WaitingQueuePromotion> findByWaitingQueueIdAndStatus(Long waitingQueueId, PromotionStatus status);

    Optional<WaitingQueuePromotion> findByReservationId(Long reservationId);

    Optional<WaitingQueuePromotion> findByResourceIdAndStartAtAndEndAtAndStatus(
            Long resourceId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            PromotionStatus status
    );

    @Query("""
            select case when count(p) > 0 then true else false end
            from WaitingQueuePromotion p
            where p.resource.id = :resourceId
              and p.status = :status
              and p.startAt < :endAt
              and p.endAt > :startAt
            """)
    boolean existsOverlapping(
            @Param("resourceId") Long resourceId,
            @Param("status") PromotionStatus status,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );

    boolean existsByResourceIdAndStatusAndExpiresAtAfter(
            Long resourceId, PromotionStatus status, LocalDateTime expiresAt);
}
