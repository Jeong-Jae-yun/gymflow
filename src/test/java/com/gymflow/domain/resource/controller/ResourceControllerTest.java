package com.gymflow.domain.resource.controller;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.dto.response.PopularResourceResponse;
import com.gymflow.domain.resource.dto.response.ReservationPolicySummaryResponse;
import com.gymflow.domain.resource.dto.response.ResourceResponse;
import com.gymflow.domain.resource.service.ResourceService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResourceController.class)
@AutoConfigureMockMvc(addFilters = false)
class ResourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResourceService resourceService;

    private ResourceResponse sampleResponse() {
        return new ResourceResponse(
                10L,
                "Chest Press A-1",
                ResourceType.MACHINE,
                ResourceStatus.ACTIVE,
                1,
                new ReservationPolicySummaryResponse(15, 15, 60)
        );
    }

    @Test
    @DisplayName("Resource 목록 조회는 200 OK와 Page 형태의 응답을 반환한다")
    void getResources_ShouldReturnOkWithPage() throws Exception {
        // given
        when(resourceService.getResources(any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1));

        // when & then
        mockMvc.perform(get("/api/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(10L))
                .andExpect(jsonPath("$.content[0].resourceType").value("MACHINE"))
                .andExpect(jsonPath("$.content[0].reservationPolicy.maxDuration").value(60))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Resource 상세 조회는 200 OK와 ResourceResponse를 반환한다")
    void getResourceDetail_ShouldReturnOk() throws Exception {
        // given
        when(resourceService.getResourceDetail(10L)).thenReturn(sampleResponse());

        // when & then
        mockMvc.perform(get("/api/resources/{resourceId}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.name").value("Chest Press A-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("존재하지 않는 Resource를 조회하면 404 Not Found를 반환한다")
    void getResourceDetail_WithNonExistentResource_ShouldReturnNotFound() throws Exception {
        // given
        when(resourceService.getResourceDetail(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/resources/{resourceId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("인기 Resource 조회는 200 OK와 순위가 매겨진 목록을 반환한다")
    void getPopularResources_ShouldReturnOkWithRankedList() throws Exception {
        // given
        when(resourceService.getPopularResources(anyInt())).thenReturn(List.of(
                new PopularResourceResponse(101L, 37L, 1),
                new PopularResourceResponse(102L, 25L, 2)));

        // when & then
        mockMvc.perform(get("/api/resources/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resourceId").value(101L))
                .andExpect(jsonPath("$[0].reservationCount").value(37))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[1].resourceId").value(102L))
                .andExpect(jsonPath("$[1].rank").value(2));
    }

    @Test
    @DisplayName("인기 Resource가 없으면 빈 배열을 반환한다")
    void getPopularResources_WithNoData_ShouldReturnEmptyArray() throws Exception {
        // given
        when(resourceService.getPopularResources(anyInt())).thenReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/resources/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
