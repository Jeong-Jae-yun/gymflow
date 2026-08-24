package com.gymflow.domain.waitingqueue.domain.repository;

import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WaitingQueuePromotionRepository extends JpaRepository<WaitingQueuePromotion, Long> {

    Optional<WaitingQueuePromotion> findByIdAndUserId(Long id, Long userId);

    boolean existsByWaitingQueueId(Long waitingQueueId);

    Optional<WaitingQueuePromotion> findByReservationId(Long reservationId);

    Optional<WaitingQueuePromotion> findByResourceIdAndStartAtAndEndAtAndStatus(
            Long resourceId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            PromotionStatus status
    );
}
