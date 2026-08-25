package com.gymflow.domain.resource.controller;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ResourceSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("토큰 없이 Resource 목록을 조회하면 401을 반환한다")
    void getResources_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/resources"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 Resource 상세를 조회하면 401을 반환한다")
    void getResourceDetail_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/resources/{resourceId}", 1L))
                .andExpect(status().isUnauthorized());
    }
}
