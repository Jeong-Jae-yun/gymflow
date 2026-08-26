package com.gymflow.domain.resource.controller;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.global.security.jwt.JwtTokenProvider;
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

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ResourceRepository resourceRepository;

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

    @Test
    @DisplayName("토큰 없이 Ranking TOP N을 조회하면 401을 반환한다")
    void getTopRankings_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/resources/rankings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 특정 Resource Ranking을 조회하면 401을 반환한다")
    void getResourceRanking_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/resources/{resourceId}/ranking", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 토큰으로 Ranking TOP N을 조회하면 200 OK를 반환한다")
    void getTopRankings_WithValidToken_ShouldReturnOk() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, "user@gymflow.com", UserRole.USER);

        mockMvc.perform(get("/api/resources/rankings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("유효한 토큰으로 특정 Resource Ranking을 조회하면 200 OK를 반환한다")
    void getResourceRanking_WithValidToken_ShouldReturnOk() throws Exception {
        Resource resource = persistResource();
        String token = jwtTokenProvider.createAccessToken(1L, "user@gymflow.com", UserRole.USER);

        mockMvc.perform(get("/api/resources/{resourceId}/ranking", resource.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private Resource persistResource() {
        Resource resource = Resource.builder()
                .name("Security Test Locker")
                .type(ResourceType.LOCKER)
                .capacity(1)
                .build();
        return resourceRepository.save(resource);
    }
}
