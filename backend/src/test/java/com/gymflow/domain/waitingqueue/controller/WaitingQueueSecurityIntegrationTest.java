package com.gymflow.domain.waitingqueue.controller;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WaitingQueueSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("토큰 없이 대기열을 등록하면 401을 반환한다")
    void register_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/waiting-queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": 1, \"startAt\": \"2026-08-12T14:00:00\", \"endAt\": \"2026-08-12T14:15:00\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 내 대기열 목록을 조회하면 401을 반환한다")
    void getMyWaitingQueues_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/waiting-queues"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 대기열을 취소하면 401을 반환한다")
    void cancel_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/waiting-queues/{waitingQueueId}", 1L))
                .andExpect(status().isUnauthorized());
    }
}
