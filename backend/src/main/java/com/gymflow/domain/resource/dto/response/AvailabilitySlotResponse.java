package com.gymflow.domain.resource.dto.response;

import java.time.LocalDateTime;

public record AvailabilitySlotResponse(
        LocalDateTime startAt,
        LocalDateTime endAt,
        boolean available
) {
}
