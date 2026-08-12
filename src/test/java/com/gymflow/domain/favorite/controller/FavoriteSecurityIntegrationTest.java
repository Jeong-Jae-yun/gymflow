package com.gymflow.domain.favorite.controller;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class FavoriteSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("토큰 없이 즐겨찾기를 추가하면 401을 반환한다")
    void addFavorite_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/favorites/{resourceId}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 즐겨찾기를 삭제하면 401을 반환한다")
    void removeFavorite_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/favorites/{resourceId}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 내 즐겨찾기 목록을 조회하면 401을 반환한다")
    void getMyFavorites_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/favorites"))
                .andExpect(status().isUnauthorized());
    }
}
