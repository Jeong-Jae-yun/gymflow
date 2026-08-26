package com.gymflow.domain.reservation.controller;

import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.service.ReservationService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationService reservationService;

    private ReservationResponse sampleResponse() {
        return new ReservationResponse(
                UUID.randomUUID(),
                100L,
                10L,
                LocalDateTime.of(2026, 8, 12, 10, 0),
                LocalDateTime.of(2026, 8, 12, 10, 30),
                ReservationStatus.CONFIRMED,
                null,
                0
        );
    }

    private ReservationResponse cancelledResponse() {
        return new ReservationResponse(
                UUID.randomUUID(),
                100L,
                10L,
                LocalDateTime.of(2026, 8, 12, 10, 0),
                LocalDateTime.of(2026, 8, 12, 10, 30),
                ReservationStatus.CANCELLED,
                CancelReason.SCHEDULE_CHANGE,
                0
        );
    }

    private ReservationResponse extendedResponse() {
        return new ReservationResponse(
                UUID.randomUUID(),
                100L,
                10L,
                LocalDateTime.of(2026, 8, 12, 10, 0),
                LocalDateTime.of(2026, 8, 12, 10, 45),
                ReservationStatus.CONFIRMED,
                null,
                1
        );
    }

    private ReservationResponse checkedInResponse() {
        return new ReservationResponse(
                UUID.randomUUID(),
                100L,
                10L,
                LocalDateTime.of(2026, 8, 12, 10, 0),
                LocalDateTime.of(2026, 8, 12, 10, 30),
                ReservationStatus.CHECKED_IN,
                null,
                0
        );
    }

    private ReservationResponse completedResponse() {
        return new ReservationResponse(
                UUID.randomUUID(),
                100L,
                10L,
                LocalDateTime.of(2026, 8, 12, 10, 0),
                LocalDateTime.of(2026, 8, 12, 10, 30),
                ReservationStatus.COMPLETED,
                null,
                0
        );
    }

    @Test
    @DisplayName("내 예약 목록 조회는 200 OK와 Page 형태의 응답을 반환한다")
    void getMyReservations_ShouldReturnOkWithPage() throws Exception {
        // given
        ReservationResponse response = sampleResponse();
        when(reservationService.getMyReservations(any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        // when & then
        mockMvc.perform(get("/api/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reservationId").value(100L))
                .andExpect(jsonPath("$.content[0].resourceId").value(10L))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("내 예약 상세 조회는 200 OK와 ReservationResponse를 반환한다")
    void getMyReservationDetail_ShouldReturnOk() throws Exception {
        // given
        ReservationResponse response = sampleResponse();
        when(reservationService.getMyReservationDetail(100L)).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/reservations/{reservationId}", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(100L))
                .andExpect(jsonPath("$.resourceId").value(10L))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("존재하지 않거나 본인 소유가 아닌 예약을 조회하면 404 Not Found를 반환한다")
    void getMyReservationDetail_WithNonExistentOrOtherUsersReservation_ShouldReturnNotFound() throws Exception {
        // given
        when(reservationService.getMyReservationDetail(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/reservations/{reservationId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESERVATION_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("정상적인 취소 요청은 200 OK와 취소된 ReservationResponse를 반환한다")
    void cancel_WithValidRequest_ShouldReturnOk() throws Exception {
        // given
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE);
        when(reservationService.cancelReservation(any(Long.class), any(CancelReservationRequest.class)))
                .thenReturn(cancelledResponse());

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(100L))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("SCHEDULE_CHANGE"));
    }

    @Test
    @DisplayName("cancelReason이 없으면 400 Bad Request를 반환한다")
    void cancel_WithMissingCancelReason_ShouldReturnBadRequest() throws Exception {
        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잘못된 형식의 cancelReason이면 400 Bad Request를 반환한다")
    void cancel_WithInvalidCancelReasonFormat_ShouldReturnBadRequest() throws Exception {
        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\": \"NOT_A_REAL_REASON\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("취소 대상 예약이 없으면 404 Not Found를 반환한다")
    void cancel_WithNonExistentReservation_ShouldReturnNotFound() throws Exception {
        // given
        CancelReservationRequest request = new CancelReservationRequest(CancelReason.SCHEDULE_CHANGE);
        when(reservationService.cancelReservation(any(Long.class), any(CancelReservationRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESERVATION_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("정상적인 연장 요청은 200 OK와 연장된 ReservationResponse를 반환한다")
    void extend_WithValidRequest_ShouldReturnOk() throws Exception {
        // given
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);
        when(reservationService.extendReservation(any(Long.class), any(ReservationExtensionRequest.class)))
                .thenReturn(extendedResponse());

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/extend", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(100L))
                .andExpect(jsonPath("$.endAt").value("2026-08-12T10:45:00"))
                .andExpect(jsonPath("$.extensionCount").value(1));
    }

    @Test
    @DisplayName("duration이 없으면 400 Bad Request를 반환한다")
    void extend_WithMissingDuration_ShouldReturnBadRequest() throws Exception {
        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/extend", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("duration이 0 이하이면 400 Bad Request를 반환한다")
    void extend_WithNonPositiveDuration_ShouldReturnBadRequest() throws Exception {
        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/extend", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"duration\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("연장 대상 예약이 없으면 404 Not Found를 반환한다")
    void extend_WithNonExistentReservation_ShouldReturnNotFound() throws Exception {
        // given
        ReservationExtensionRequest request = new ReservationExtensionRequest(15);
        when(reservationService.extendReservation(any(Long.class), any(ReservationExtensionRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/extend", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESERVATION_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("정상적인 체크인 요청은 200 OK와 CHECKED_IN 상태의 ReservationResponse를 반환한다")
    void checkIn_WithValidRequest_ShouldReturnOk() throws Exception {
        // given
        when(reservationService.checkInReservation(100L)).thenReturn(checkedInResponse());

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-in", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(100L))
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    }

    @Test
    @DisplayName("체크인 대상 예약이 없으면 404 Not Found를 반환한다")
    void checkIn_WithNonExistentReservation_ShouldReturnNotFound() throws Exception {
        // given
        when(reservationService.checkInReservation(999L))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-in", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESERVATION_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("체크인할 수 없는 상태의 예약이면 409 Conflict를 반환한다")
    void checkIn_WithNonConfirmedReservation_ShouldReturnConflict() throws Exception {
        // given
        when(reservationService.checkInReservation(100L))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_NOT_CHECKINABLE));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-in", 100L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESERVATION_NOT_CHECKINABLE.getMessage()));
    }

    @Test
    @DisplayName("Promotion 체크인 deadline을 넘긴 요청은 500이 아니라 409 Conflict를 반환한다")
    void checkIn_WithExpiredPromotionCheckInDeadline_ShouldReturnConflictNotServerError() throws Exception {
        // given
        when(reservationService.checkInReservation(100L))
                .thenThrow(new BusinessException(ErrorCode.PROMOTION_CHECKIN_EXPIRED));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-in", 100L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.PROMOTION_CHECKIN_EXPIRED.getMessage()));
    }

    @Test
    @DisplayName("정상적인 체크아웃 요청은 200 OK와 COMPLETED 상태의 ReservationResponse를 반환한다")
    void checkOut_WithValidRequest_ShouldReturnOk() throws Exception {
        // given
        when(reservationService.checkOutReservation(100L)).thenReturn(completedResponse());

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-out", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(100L))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("체크아웃 대상 예약이 없으면 404 Not Found를 반환한다")
    void checkOut_WithNonExistentReservation_ShouldReturnNotFound() throws Exception {
        // given
        when(reservationService.checkOutReservation(999L))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-out", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESERVATION_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("체크아웃할 수 없는 상태의 예약이면 409 Conflict를 반환한다")
    void checkOut_WithNonCheckedInReservation_ShouldReturnConflict() throws Exception {
        // given
        when(reservationService.checkOutReservation(100L))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_NOT_CHECKOUTABLE));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-out", 100L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESERVATION_NOT_CHECKOUTABLE.getMessage()));
    }
}
