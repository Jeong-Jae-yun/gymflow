package com.gymflow.domain.reservation.dto.response;

import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationBatchId,
        Long reservationId,
        Long resourceId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ReservationStatus status,
        CancelReason cancelReason
) {
}
