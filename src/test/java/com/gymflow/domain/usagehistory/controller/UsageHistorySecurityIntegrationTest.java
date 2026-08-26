package com.gymflow.domain.usagehistory.controller;

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
class UsageHistorySecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ResourceRepository resourceRepository;

    @Test
    @DisplayName("토큰 없이 내 이용 이력을 조회하면 401을 반환한다")
    void getMyUsageHistories_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/usage-histories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰 없이 내 이용 통계를 조회하면 401을 반환한다")
    void getMyStatistics_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/usage-histories/statistics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 토큰으로 내 이용 이력을 조회하면 200 OK를 반환한다")
    void getMyUsageHistories_WithValidToken_ShouldReturnOk() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, "user@gymflow.com", UserRole.USER);

        mockMvc.perform(get("/api/usage-histories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("유효한 토큰으로 내 이용 통계를 조회하면 200 OK를 반환한다")
    void getMyStatistics_WithValidToken_ShouldReturnOk() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, "user@gymflow.com", UserRole.USER);

        mockMvc.perform(get("/api/usage-histories/statistics")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 없이 관리자 Resource 통계를 조회하면 401을 반환한다")
    void getResourceStatistics_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        Resource resource = persistResource();

        mockMvc.perform(get("/api/admin/resources/{resourceId}/statistics", resource.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER 권한으로 관리자 Resource 통계를 조회하면 403을 반환한다")
    void getResourceStatistics_WithUserRole_ShouldReturnForbidden() throws Exception {
        Resource resource = persistResource();
        String token = jwtTokenProvider.createAccessToken(1L, "user@gymflow.com", UserRole.USER);

        mockMvc.perform(get("/api/admin/resources/{resourceId}/statistics", resource.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 권한으로 관리자 Resource 통계를 조회하면 200 OK를 반환한다")
    void getResourceStatistics_WithAdminRole_ShouldReturnOk() throws Exception {
        Resource resource = persistResource();
        String token = jwtTokenProvider.createAccessToken(2L, "admin@gymflow.com", UserRole.ADMIN);

        mockMvc.perform(get("/api/admin/resources/{resourceId}/statistics", resource.getId())
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
