package com.gymflow.domain.usagehistory.service;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.usagehistory.domain.repository.UsageHistoryRepository;
import com.gymflow.domain.usagehistory.domain.repository.projection.ResourceUsageStatisticsProjection;
import com.gymflow.domain.usagehistory.domain.repository.projection.UsageSummaryProjection;
import com.gymflow.domain.usagehistory.dto.response.AdminResourceUsageStatisticsResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageHistoryResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageStatisticsResponse;
import com.gymflow.domain.usagehistory.mapper.UsageHistoryMapper;
import com.gymflow.global.common.dto.PageResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsageHistoryService {

    private final UsageHistoryRepository usageHistoryRepository;
    private final ResourceRepository resourceRepository;

    public PageResponse<UsageHistoryResponse> getMyUsageHistories(int page, int size, LocalDateTime from, LocalDateTime to) {
        validateDateRange(from, to);
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Pageable pageable = PageRequest.of(page, size);
        Page<UsageHistoryResponse> result = usageHistoryRepository
                .findMyUsageHistories(currentUserId, from, to, pageable)
                .map(UsageHistoryMapper::toResponse);

        return PageResponse.from(result);
    }

    public UsageStatisticsResponse getMyStatistics(LocalDateTime from, LocalDateTime to) {
        validateDateRange(from, to);
        Long currentUserId = SecurityUtils.getCurrentUserId();

        UsageSummaryProjection summary = usageHistoryRepository.findUsageSummaryByUserId(currentUserId, from, to);
        List<ResourceUsageStatisticsProjection> resourceUsages =
                usageHistoryRepository.findResourceUsageStatisticsByUserId(currentUserId, from, to);

        return UsageHistoryMapper.toStatisticsResponse(summary, resourceUsages);
    }

    public AdminResourceUsageStatisticsResponse getResourceStatistics(Long resourceId, LocalDateTime from, LocalDateTime to) {
        validateDateRange(from, to);

        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        UsageSummaryProjection summary = usageHistoryRepository.findUsageSummaryByResourceId(resourceId, from, to);

        return UsageHistoryMapper.toAdminResponse(resource, summary);
    }

    private void validateDateRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }
    }
}
