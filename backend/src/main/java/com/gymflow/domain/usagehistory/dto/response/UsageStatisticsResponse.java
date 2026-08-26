package com.gymflow.domain.usagehistory.dto.response;

import java.util.List;

public record UsageStatisticsResponse(
        long totalUsageCount,
        long totalUsageMinutes,
        List<ResourceUsageStatisticsResponse> resourceUsages
) {
}
