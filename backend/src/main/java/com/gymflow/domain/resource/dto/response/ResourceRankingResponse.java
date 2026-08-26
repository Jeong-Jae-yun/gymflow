package com.gymflow.domain.resource.dto.response;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;

public record ResourceRankingResponse(
        Long rank,
        Long resourceId,
        String resourceName,
        ResourceType resourceType,
        ResourceStatus resourceStatus,
        Long score
) {
}
