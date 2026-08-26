package com.gymflow.domain.usagehistory.domain.repository.projection;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;

public interface ResourceUsageStatisticsProjection {

    Long getResourceId();

    String getResourceName();

    ResourceType getResourceType();

    ResourceStatus getResourceStatus();

    Long getUsageCount();

    Long getTotalUsageMinutes();
}
