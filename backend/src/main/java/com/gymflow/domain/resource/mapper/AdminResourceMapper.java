package com.gymflow.domain.resource.mapper;

import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.dto.response.AdminResourceResponse;

public final class AdminResourceMapper {

    private AdminResourceMapper() {
    }

    public static AdminResourceResponse toResponse(Resource resource, String imageUrl) {
        ReservationPolicy policy = resource.getReservationPolicy();
        return new AdminResourceResponse(
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getStatus(),
                resource.getCapacity(),
                resource.getDescription(),
                policy != null ? policy.getSlotDuration() : null,
                policy != null ? policy.getMinDuration() : null,
                policy != null ? policy.getMaxDuration() : null,
                imageUrl,
                resource.getCreatedAt(),
                resource.getUpdatedAt()
        );
    }
}
