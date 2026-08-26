package com.gymflow.domain.usagehistory.controller;

import com.gymflow.domain.usagehistory.dto.response.UsageHistoryResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageStatisticsResponse;
import com.gymflow.domain.usagehistory.service.UsageHistoryService;
import com.gymflow.global.common.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/usage-histories")
@RequiredArgsConstructor
public class UsageHistoryController {

    private final UsageHistoryService usageHistoryService;

    @GetMapping
    public ResponseEntity<PageResponse<UsageHistoryResponse>> getMyUsageHistories(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        PageResponse<UsageHistoryResponse> response = usageHistoryService.getMyUsageHistories(page, size, from, to);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/statistics")
    public ResponseEntity<UsageStatisticsResponse> getMyStatistics(
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        UsageStatisticsResponse response = usageHistoryService.getMyStatistics(from, to);
        return ResponseEntity.ok(response);
    }
}
