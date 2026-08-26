package com.gymflow.domain.reservation.dto.request;

import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import jakarta.validation.constraints.NotNull;

public record CancelReservationRequest(

        @NotNull
        CancelReason cancelReason
) {
}
