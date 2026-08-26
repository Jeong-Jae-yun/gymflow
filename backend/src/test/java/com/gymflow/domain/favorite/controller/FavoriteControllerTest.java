package com.gymflow.domain.favorite.controller;

import com.gymflow.domain.favorite.dto.response.FavoriteResponse;
import com.gymflow.domain.favorite.service.FavoriteService;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FavoriteController.class)
@AutoConfigureMockMvc(addFilters = false)
class FavoriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FavoriteService favoriteService;

    private FavoriteResponse sampleResponse() {
        return new FavoriteResponse(100L, 10L, LocalDateTime.of(2026, 8, 12, 10, 0));
    }

    @Test
    @DisplayName("즐겨찾기 추가는 201 Created와 FavoriteResponse를 반환한다")
    void addFavorite_WithValidRequest_ShouldReturnCreated() throws Exception {
        // given
        when(favoriteService.addFavorite(10L)).thenReturn(sampleResponse());

        // when & then
        mockMvc.perform(post("/api/favorites/{resourceId}", 10L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.favoriteId").value(100L))
                .andExpect(jsonPath("$.resourceId").value(10L));
    }

    @Test
    @DisplayName("존재하지 않는 Resource를 즐겨찾기하면 404 Not Found를 반환한다")
    void addFavorite_WithNonExistentResource_ShouldReturnNotFound() throws Exception {
        // given
        when(favoriteService.addFavorite(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/api/favorites/{resourceId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("이미 즐겨찾기한 Resource이면 409 Conflict를 반환한다")
    void addFavorite_WithDuplicateFavorite_ShouldReturnConflict() throws Exception {
        // given
        when(favoriteService.addFavorite(anyLong()))
                .thenThrow(new BusinessException(ErrorCode.FAVORITE_ALREADY_EXISTS));

        // when & then
        mockMvc.perform(post("/api/favorites/{resourceId}", 10L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.FAVORITE_ALREADY_EXISTS.getMessage()));
    }

    @Test
    @DisplayName("즐겨찾기 삭제는 204 No Content를 반환한다")
    void removeFavorite_WithValidRequest_ShouldReturnNoContent() throws Exception {
        // given
        doNothing().when(favoriteService).removeFavorite(10L);

        // when & then
        mockMvc.perform(delete("/api/favorites/{resourceId}", 10L))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("존재하지 않는 즐겨찾기를 삭제하면 404 Not Found를 반환한다")
    void removeFavorite_WithNonExistentFavorite_ShouldReturnNotFound() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.FAVORITE_NOT_FOUND))
                .when(favoriteService).removeFavorite(anyLong());

        // when & then
        mockMvc.perform(delete("/api/favorites/{resourceId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.FAVORITE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("내 즐겨찾기 목록 조회는 200 OK와 목록을 반환한다")
    void getMyFavorites_ShouldReturnOkWithList() throws Exception {
        // given
        when(favoriteService.getMyFavorites()).thenReturn(List.of(sampleResponse()));

        // when & then
        mockMvc.perform(get("/api/favorites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].favoriteId").value(100L))
                .andExpect(jsonPath("$[0].resourceId").value(10L));
    }
}
