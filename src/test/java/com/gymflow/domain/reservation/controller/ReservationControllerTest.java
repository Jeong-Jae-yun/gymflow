package com.gymflow.domain.reservation.controller;

import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    private ReservationResponse sampleResponse() {
        return new ReservationResponse(
                UUID.randomUUID(),
                100L,
                10L,
                LocalDateTime.of(2026, 8, 12, 10, 0),
                LocalDateTime.of(2026, 8, 12, 10, 30),
                ReservationStatus.CONFIRMED
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
}
