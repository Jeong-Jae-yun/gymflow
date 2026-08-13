package com.gymflow.domain.waitingqueue.controller;

import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;
import com.gymflow.domain.waitingqueue.service.WaitingQueueService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WaitingQueueController.class)
@AutoConfigureMockMvc(addFilters = false)
class WaitingQueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WaitingQueueService waitingQueueService;

    private WaitingQueueResponse sampleResponse() {
        return new WaitingQueueResponse(
                100L,
                10L,
                LocalDateTime.of(2026, 8, 12, 14, 0),
                LocalDateTime.of(2026, 8, 12, 14, 15),
                WaitingQueueStatus.WAITING,
                LocalDateTime.of(2026, 8, 12, 10, 0)
        );
    }

    @Test
    @DisplayName("대기열 등록은 201 Created와 WaitingQueueResponse를 반환한다")
    void register_WithValidRequest_ShouldReturnCreated() throws Exception {
        // given
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(
                10L, LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        when(waitingQueueService.registerWaitingQueue(any(WaitingQueueCreateRequest.class)))
                .thenReturn(sampleResponse());

        // when & then
        mockMvc.perform(post("/api/waiting-queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waitingQueueId").value(100L))
                .andExpect(jsonPath("$.resourceId").value(10L))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("resourceId가 없으면 400 Bad Request를 반환한다")
    void register_WithMissingResourceId_ShouldReturnBadRequest() throws Exception {
        // when & then
        mockMvc.perform(post("/api/waiting-queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\": \"2026-08-12T14:00:00\", \"endAt\": \"2026-08-12T14:15:00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("startAt이 없으면 400 Bad Request를 반환한다")
    void register_WithMissingStartAt_ShouldReturnBadRequest() throws Exception {
        // when & then
        mockMvc.perform(post("/api/waiting-queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": 10, \"endAt\": \"2026-08-12T14:15:00\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 Resource로 대기열을 등록하면 404 Not Found를 반환한다")
    void register_WithNonExistentResource_ShouldReturnNotFound() throws Exception {
        // given
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(
                999L, LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        when(waitingQueueService.registerWaitingQueue(any(WaitingQueueCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/api/waiting-queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("이미 예약 가능한 시간대이면 409 Conflict를 반환한다")
    void register_WithAvailableTimeSlot_ShouldReturnConflict() throws Exception {
        // given
        WaitingQueueCreateRequest request = new WaitingQueueCreateRequest(
                10L, LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 15));
        when(waitingQueueService.registerWaitingQueue(any(WaitingQueueCreateRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.WAITING_QUEUE_NOT_AVAILABLE));

        // when & then
        mockMvc.perform(post("/api/waiting-queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.WAITING_QUEUE_NOT_AVAILABLE.getMessage()));
    }

    @Test
    @DisplayName("내 대기열 목록 조회는 200 OK와 Page 형태의 응답을 반환한다")
    void getMyWaitingQueues_ShouldReturnOkWithPage() throws Exception {
        // given
        when(waitingQueueService.getMyWaitingQueues(any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1));

        // when & then
        mockMvc.perform(get("/api/waiting-queues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].waitingQueueId").value(100L))
                .andExpect(jsonPath("$.content[0].resourceId").value(10L))
                .andExpect(jsonPath("$.content[0].status").value("WAITING"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("대기열 취소는 204 No Content를 반환한다")
    void cancel_WithValidRequest_ShouldReturnNoContent() throws Exception {
        // given
        doNothing().when(waitingQueueService).cancelWaitingQueue(100L);

        // when & then
        mockMvc.perform(delete("/api/waiting-queues/{waitingQueueId}", 100L))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("존재하지 않는 대기열을 취소하면 404 Not Found를 반환한다")
    void cancel_WithNonExistentWaitingQueue_ShouldReturnNotFound() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.WAITING_QUEUE_NOT_FOUND))
                .when(waitingQueueService).cancelWaitingQueue(anyLong());

        // when & then
        mockMvc.perform(delete("/api/waiting-queues/{waitingQueueId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.WAITING_QUEUE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("취소할 수 없는 상태의 대기열이면 409 Conflict를 반환한다")
    void cancel_WithNotCancellableStatus_ShouldReturnConflict() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.WAITING_QUEUE_NOT_CANCELLABLE))
                .when(waitingQueueService).cancelWaitingQueue(100L);

        // when & then
        mockMvc.perform(delete("/api/waiting-queues/{waitingQueueId}", 100L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.WAITING_QUEUE_NOT_CANCELLABLE.getMessage()));
    }
}
