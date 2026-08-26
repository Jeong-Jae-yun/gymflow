package com.gymflow.domain.usagehistory.domain.repository.projection;

public interface UsageSummaryProjection {

    Long getTotalUsageCount();

    Long getTotalUsageMinutes();
}
