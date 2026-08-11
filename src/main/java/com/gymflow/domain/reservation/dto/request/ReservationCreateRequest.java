package com.gymflow.domain.reservation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record ReservationCreateRequest(

        @NotNull
        Long resourceId,

        @NotNull
        LocalDateTime startAt,

        @NotNull
        @Positive
        Integer duration
) {
}
