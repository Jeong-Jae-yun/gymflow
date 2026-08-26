package com.gymflow.domain.usagehistory.mapper;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.projection.ResourceUsageStatisticsProjection;
import com.gymflow.domain.usagehistory.domain.repository.projection.UsageSummaryProjection;
import com.gymflow.domain.usagehistory.dto.response.AdminResourceUsageStatisticsResponse;
import com.gymflow.domain.usagehistory.dto.response.ResourceUsageStatisticsResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageHistoryResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageStatisticsResponse;

import java.util.List;

public final class UsageHistoryMapper {

    private UsageHistoryMapper() {
    }

    public static UsageHistoryResponse toResponse(UsageHistory usageHistory) {
        Resource resource = usageHistory.getResource();
        return new UsageHistoryResponse(
                usageHistory.getId(),
                usageHistory.getReservation().getId(),
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getStatus(),
                usageHistory.getStartedAt(),
                usageHistory.getEndedAt(),
                usageHistory.getDuration()
        );
    }

    public static UsageStatisticsResponse toStatisticsResponse(
            UsageSummaryProjection summary, List<ResourceUsageStatisticsProjection> resourceUsages) {
        List<ResourceUsageStatisticsResponse> resourceUsageResponses = resourceUsages.stream()
                .map(UsageHistoryMapper::toResourceUsageStatisticsResponse)
                .toList();

        return new UsageStatisticsResponse(
                summary.getTotalUsageCount(),
                summary.getTotalUsageMinutes(),
                resourceUsageResponses
        );
    }

    private static ResourceUsageStatisticsResponse toResourceUsageStatisticsResponse(
            ResourceUsageStatisticsProjection projection) {
        return new ResourceUsageStatisticsResponse(
                projection.getResourceId(),
                projection.getResourceName(),
                projection.getResourceType(),
                projection.getResourceStatus(),
                projection.getUsageCount(),
                projection.getTotalUsageMinutes()
        );
    }

    public static AdminResourceUsageStatisticsResponse toAdminResponse(Resource resource, UsageSummaryProjection summary) {
        return new AdminResourceUsageStatisticsResponse(
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getStatus(),
                summary.getTotalUsageCount(),
                summary.getTotalUsageMinutes()
        );
    }
}
