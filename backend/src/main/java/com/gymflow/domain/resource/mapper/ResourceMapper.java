package com.gymflow.domain.resource.mapper;

import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.dto.response.ReservationPolicySummaryResponse;
import com.gymflow.domain.resource.dto.response.ResourceResponse;

public final class ResourceMapper {

    private ResourceMapper() {
    }

    public static ResourceResponse toResponse(Resource resource, String imageUrl) {
        return new ResourceResponse(
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getStatus(),
                resource.getCapacity(),
                toReservationPolicySummary(resource.getReservationPolicy()),
                imageUrl
        );
    }

    private static ReservationPolicySummaryResponse toReservationPolicySummary(ReservationPolicy policy) {
        if (policy == null) {
            return null;
        }
        return new ReservationPolicySummaryResponse(
                policy.getSlotDuration(),
                policy.getMinDuration(),
                policy.getMaxDuration()
        );
    }
}
