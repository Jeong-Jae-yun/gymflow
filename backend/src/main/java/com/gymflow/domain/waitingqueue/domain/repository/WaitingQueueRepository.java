package com.gymflow.domain.waitingqueue.domain.repository;

import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WaitingQueueRepository extends JpaRepository<WaitingQueue, Long> {

    Page<WaitingQueue> findAllByUserId(Long userId, Pageable pageable);

    Optional<WaitingQueue> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
            Long userId,
            Long resourceId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            WaitingQueueStatus status
    );
}
