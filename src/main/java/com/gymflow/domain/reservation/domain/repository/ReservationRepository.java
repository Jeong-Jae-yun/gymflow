package com.gymflow.domain.reservation.domain.repository;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Page<Reservation> findAllByUserId(Long userId, Pageable pageable);

    Optional<Reservation> findByIdAndUserId(Long id, Long userId);

    @Query("""
            select case when count(r) > 0 then true else false end
            from Reservation r
            where r.resource.id = :resourceId
              and r.status = :status
              and r.startAt < :endAt
              and r.endAt > :startAt
            """)
    boolean existsOverlapping(
            @Param("resourceId") Long resourceId,
            @Param("status") ReservationStatus status,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );
}
