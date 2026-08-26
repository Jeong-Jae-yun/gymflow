package com.gymflow.domain.usagehistory.dto.response;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;

public record AdminResourceUsageStatisticsResponse(
        Long resourceId,
        String resourceName,
        ResourceType resourceType,
        ResourceStatus resourceStatus,
        long totalUsageCount,
        long totalUsageMinutes
) {
}
