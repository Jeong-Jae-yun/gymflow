package com.gymflow.domain.reservation.mapper;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;

public final class ReservationMapper {

    private ReservationMapper() {
    }

    public static ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.getReservationBatchId(),
                reservation.getId(),
                reservation.getResource().getId(),
                reservation.getStartAt(),
                reservation.getEndAt(),
                reservation.getStatus()
        );
    }
}
