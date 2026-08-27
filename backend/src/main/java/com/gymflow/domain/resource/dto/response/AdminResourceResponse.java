package com.gymflow.domain.resource.dto.response;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;

import java.time.LocalDateTime;

public record AdminResourceResponse(
        Long id,
        String name,
        ResourceType type,
        ResourceStatus status,
        Integer capacity,
        String description,
        Integer slotDuration,
        Integer minDuration,
        Integer maxDuration,
        String imageUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
