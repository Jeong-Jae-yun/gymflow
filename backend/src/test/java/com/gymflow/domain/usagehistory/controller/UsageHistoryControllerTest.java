package com.gymflow.domain.usagehistory.controller;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.usagehistory.dto.response.ResourceUsageStatisticsResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageHistoryResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageStatisticsResponse;
import com.gymflow.domain.usagehistory.service.UsageHistoryService;
import com.gymflow.global.common.dto.PageResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UsageHistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class UsageHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UsageHistoryService usageHistoryService;

    private UsageHistoryResponse sampleResponse() {
        return new UsageHistoryResponse(
                1L, 100L, 10L, "Chest Press A-1", ResourceType.MACHINE, ResourceStatus.ACTIVE,
                LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 20), 20);
    }

    @Test
    @DisplayName("내 이용 이력 조회는 200 OK와 PageResponse를 반환한다")
    void getMyUsageHistories_ShouldReturnOkWithPageResponse() throws Exception {
        // given
        PageResponse<UsageHistoryResponse> response =
                new PageResponse<>(List.of(sampleResponse()), 0, 20, 1, 1, true, true);
        when(usageHistoryService.getMyUsageHistories(anyInt(), anyInt(), any(), any())).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/usage-histories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1L))
                .andExpect(jsonPath("$.content[0].resourceId").value(10L))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @DisplayName("page가 음수이면 400 Bad Request를 반환한다")
    void getMyUsageHistories_WithNegativePage_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/usage-histories").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size가 0이면 400 Bad Request를 반환한다")
    void getMyUsageHistories_WithZeroSize_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/usage-histories").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size가 100을 초과하면 400 Bad Request를 반환한다")
    void getMyUsageHistories_WithSizeOver100_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/usage-histories").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("from/to 형식이 올바르지 않으면 400 Bad Request를 반환한다")
    void getMyUsageHistories_WithInvalidDateFormat_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/usage-histories").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("기간 범위가 잘못되면 400 Bad Request를 반환한다")
    void getMyUsageHistories_WithInvalidDateRange_ShouldReturnBadRequest() throws Exception {
        // given
        when(usageHistoryService.getMyUsageHistories(anyInt(), anyInt(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_DATE_RANGE));

        // when & then
        mockMvc.perform(get("/api/usage-histories")
                        .param("from", "2026-09-01T00:00:00")
                        .param("to", "2026-08-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_DATE_RANGE.getMessage()));
    }

    @Test
    @DisplayName("내 이용 통계 조회는 200 OK와 통계 응답을 반환한다")
    void getMyStatistics_ShouldReturnOk() throws Exception {
        // given
        UsageStatisticsResponse response = new UsageStatisticsResponse(
                3L, 90L,
                List.of(new ResourceUsageStatisticsResponse(10L, "Chest Press A-1", ResourceType.MACHINE, ResourceStatus.ACTIVE, 3L, 90L)));
        when(usageHistoryService.getMyStatistics(any(), any())).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/usage-histories/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsageCount").value(3))
                .andExpect(jsonPath("$.totalUsageMinutes").value(90))
                .andExpect(jsonPath("$.resourceUsages[0].resourceId").value(10L));
    }

    @Test
    @DisplayName("UsageHistory가 없으면 통계는 0/0/빈 목록으로 200 OK를 반환한다")
    void getMyStatistics_WithNoUsageHistory_ShouldReturnZeroStatistics() throws Exception {
        // given
        UsageStatisticsResponse response = new UsageStatisticsResponse(0L, 0L, List.of());
        when(usageHistoryService.getMyStatistics(any(), any())).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/usage-histories/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsageCount").value(0))
                .andExpect(jsonPath("$.totalUsageMinutes").value(0))
                .andExpect(jsonPath("$.resourceUsages").isEmpty());
    }

    @Test
    @DisplayName("통계 조회에서 기간 범위가 잘못되면 400 Bad Request를 반환한다")
    void getMyStatistics_WithInvalidDateRange_ShouldReturnBadRequest() throws Exception {
        // given
        when(usageHistoryService.getMyStatistics(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_DATE_RANGE));

        // when & then
        mockMvc.perform(get("/api/usage-histories/statistics")
                        .param("from", "2026-08-01T00:00:00")
                        .param("to", "2026-08-01T00:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_DATE_RANGE.getMessage()));
    }
}
