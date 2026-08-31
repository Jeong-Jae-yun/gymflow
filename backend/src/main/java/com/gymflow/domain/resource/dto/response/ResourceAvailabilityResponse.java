package com.gymflow.domain.resource.dto.response;

import java.time.LocalDate;
import java.util.List;

public record ResourceAvailabilityResponse(
        Long resourceId,
        LocalDate date,
        Integer slotDuration,
        Integer minDuration,
        Integer maxDuration,
        List<AvailabilitySlotResponse> slots
) {
}
