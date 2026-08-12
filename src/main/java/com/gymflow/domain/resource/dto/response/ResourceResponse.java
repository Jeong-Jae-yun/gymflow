package com.gymflow.domain.resource.dto.response;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;

public record ResourceResponse(
        Long id,
        String name,
        ResourceType resourceType,
        ResourceStatus status,
        Integer capacity,
        ReservationPolicySummaryResponse reservationPolicy
) {
}
