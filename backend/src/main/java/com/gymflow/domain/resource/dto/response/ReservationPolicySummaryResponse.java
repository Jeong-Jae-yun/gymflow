package com.gymflow.domain.resource.dto.response;

public record ReservationPolicySummaryResponse(
        Integer slotDuration,
        Integer minDuration,
        Integer maxDuration
) {
}
