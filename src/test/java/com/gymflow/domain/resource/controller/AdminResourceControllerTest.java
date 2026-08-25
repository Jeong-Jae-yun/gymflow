package com.gymflow.domain.resource.controller;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.dto.response.AdminResourceResponse;
import com.gymflow.domain.resource.service.AdminResourceService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminResourceController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminResourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminResourceService adminResourceService;

    private AdminResourceResponse sampleResponse() {
        return new AdminResourceResponse(
                10L, "Chest Press A-1", ResourceType.MACHINE, ResourceStatus.ACTIVE, 1, "3F Weight Zone",
                15, 15, 60, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("유효한 요청으로 Resource를 생성하면 201 Created와 응답 본문을 반환한다")
    void create_WithValidRequest_ShouldReturnCreated() throws Exception {
        // given
        when(adminResourceService.createResource(any())).thenReturn(sampleResponse());
        Map<String, Object> request = Map.of(
                "name", "Chest Press A-1",
                "type", "MACHINE",
                "capacity", 1,
                "description", "3F Weight Zone",
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );

        // when & then
        mockMvc.perform(post("/api/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.type").value("MACHINE"));
    }

    @Test
    @DisplayName("name이 비어있으면 Resource 생성 요청은 400 Bad Request를 반환한다")
    void create_WithBlankName_ShouldReturnBadRequest() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "",
                "type", "MACHINE",
                "capacity", 1,
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );

        // when & then
        mockMvc.perform(post("/api/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("type이 없으면 Resource 생성 요청은 400 Bad Request를 반환한다")
    void create_WithoutType_ShouldReturnBadRequest() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "Chest Press A-1",
                "capacity", 1,
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );

        // when & then
        mockMvc.perform(post("/api/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("capacity가 1 미만이면 Resource 생성 요청은 400 Bad Request를 반환한다")
    void create_WithCapacityBelowOne_ShouldReturnBadRequest() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "Chest Press A-1",
                "type", "MACHINE",
                "capacity", 0,
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );

        // when & then
        mockMvc.perform(post("/api/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잘못된 Policy 조합으로 생성 요청 시 409 Conflict가 아닌 400 Bad Request를 반환한다")
    void create_WithInvalidPolicyCombination_ShouldReturnBadRequest() throws Exception {
        // given
        when(adminResourceService.createResource(any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_RESERVATION_POLICY));
        Map<String, Object> request = Map.of(
                "name", "Chest Press A-1",
                "type", "MACHINE",
                "capacity", 1,
                "slotDuration", 15,
                "minDuration", 60,
                "maxDuration", 30
        );

        // when & then
        mockMvc.perform(post("/api/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_RESERVATION_POLICY.getMessage()));
    }

    @Test
    @DisplayName("유효한 요청으로 Resource를 수정하면 200 OK와 응답 본문을 반환한다")
    void update_WithValidRequest_ShouldReturnOk() throws Exception {
        // given
        when(adminResourceService.updateResource(eq(10L), any())).thenReturn(sampleResponse());
        Map<String, Object> request = Map.of(
                "name", "Chest Press A-1",
                "capacity", 1,
                "description", "3F Weight Zone",
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );

        // when & then
        mockMvc.perform(put("/api/admin/resources/{resourceId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L));
    }

    @Test
    @DisplayName("존재하지 않는 Resource를 수정하면 404 Not Found를 반환한다")
    void update_WithNonExistentResource_ShouldReturnNotFound() throws Exception {
        // given
        when(adminResourceService.updateResource(anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Map<String, Object> request = Map.of(
                "name", "Chest Press A-1",
                "capacity", 1,
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );

        // when & then
        mockMvc.perform(put("/api/admin/resources/{resourceId}", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("유효한 요청으로 상태를 변경하면 200 OK와 응답 본문을 반환한다")
    void changeStatus_WithValidRequest_ShouldReturnOk() throws Exception {
        // given
        when(adminResourceService.changeStatus(eq(10L), any())).thenReturn(sampleResponse());

        // when & then
        mockMvc.perform(patch("/api/admin/resources/{resourceId}/status", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L));
    }

    @Test
    @DisplayName("status가 없으면 상태 변경 요청은 400 Bad Request를 반환한다")
    void changeStatus_WithoutStatus_ShouldReturnBadRequest() throws Exception {
        // when & then
        mockMvc.perform(patch("/api/admin/resources/{resourceId}/status", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("활성 예약이 존재하는 상태 변경 요청은 409 Conflict를 반환한다")
    void changeStatus_WithActiveReservationConflict_ShouldReturnConflict() throws Exception {
        // given
        when(adminResourceService.changeStatus(eq(10L), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_STATUS_CHANGE_NOT_ALLOWED));

        // when & then
        mockMvc.perform(patch("/api/admin/resources/{resourceId}/status", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_STATUS_CHANGE_NOT_ALLOWED.getMessage()));
    }

    @Test
    @DisplayName("존재하지 않는 Resource의 상태를 변경하면 404 Not Found를 반환한다")
    void changeStatus_WithNonExistentResource_ShouldReturnNotFound() throws Exception {
        // given
        when(adminResourceService.changeStatus(anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // when & then
        mockMvc.perform(patch("/api/admin/resources/{resourceId}/status", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isNotFound());
    }
}
