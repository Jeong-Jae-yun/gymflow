package com.gymflow.domain.reservation.controller;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("토큰 없이 내 예약 목록을 조회하면 401을 반환한다")
    void getMyReservations_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/reservations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 예약 상세를 조회하면 401을 반환한다")
    void getMyReservationDetail_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/reservations/{reservationId}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 예약을 연장하면 401을 반환한다")
    void extendReservation_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/reservations/{reservationId}/extend", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"duration\": 15}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 예약을 체크인하면 401을 반환한다")
    void checkInReservation_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-in", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 예약을 체크아웃하면 401을 반환한다")
    void checkOutReservation_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/reservations/{reservationId}/check-out", 1L))
                .andExpect(status().isUnauthorized());
    }
}
