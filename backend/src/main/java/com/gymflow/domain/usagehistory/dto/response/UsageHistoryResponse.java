package com.gymflow.domain.usagehistory.dto.response;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;

import java.time.LocalDateTime;

public record UsageHistoryResponse(
        Long id,
        Long reservationId,
        Long resourceId,
        String resourceName,
        ResourceType resourceType,
        ResourceStatus resourceStatus,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Integer duration
) {
}
