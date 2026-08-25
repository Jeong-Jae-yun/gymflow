package com.gymflow.domain.resource.controller;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminResourceSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ResourceRepository resourceRepository;

    private String adminToken() {
        return jwtTokenProvider.createAccessToken(1L, "admin@gymflow.com", UserRole.ADMIN);
    }

    private String userToken() {
        return jwtTokenProvider.createAccessToken(2L, "user@gymflow.com", UserRole.USER);
    }

    private Resource persistResourceWithPolicy(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(60)
                .build();
        return resourceRepository.save(resource);
    }

    private Map<String, Object> createRequestBody() {
        return Map.of(
                "name", "Security Test Resource " + System.nanoTime(),
                "type", "MACHINE",
                "capacity", 1,
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );
    }

    private Map<String, Object> updateRequestBody() {
        return Map.of(
                "name", "Updated Resource " + System.nanoTime(),
                "capacity", 2,
                "slotDuration", 15,
                "minDuration", 15,
                "maxDuration", 60
        );
    }

    @Test
    @DisplayName("ADMIN 권한으로 Resource를 생성하면 201 Created를 반환한다")
    void create_WithAdminRole_ShouldReturnCreated() throws Exception {
        mockMvc.perform(post("/api/admin/resources")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequestBody())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("USER 권한으로 Resource 생성을 요청하면 403 Forbidden을 반환한다")
    void create_WithUserRole_ShouldReturnForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/resources")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequestBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰 없이 Resource 생성을 요청하면 401 Unauthorized를 반환한다")
    void create_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/api/admin/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequestBody())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN 권한으로 Resource를 수정하면 200 OK를 반환한다")
    void update_WithAdminRole_ShouldReturnOk() throws Exception {
        Resource resource = persistResourceWithPolicy("Admin Update Target " + System.nanoTime());

        mockMvc.perform(put("/api/admin/resources/{resourceId}", resource.getId())
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequestBody())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 권한으로 Resource 수정을 요청하면 403 Forbidden을 반환한다")
    void update_WithUserRole_ShouldReturnForbidden() throws Exception {
        Resource resource = persistResourceWithPolicy("User Update Target " + System.nanoTime());

        mockMvc.perform(put("/api/admin/resources/{resourceId}", resource.getId())
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequestBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰 없이 Resource 수정을 요청하면 401 Unauthorized를 반환한다")
    void update_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        Resource resource = persistResourceWithPolicy("No Token Update Target " + System.nanoTime());

        mockMvc.perform(put("/api/admin/resources/{resourceId}", resource.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequestBody())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ADMIN 권한으로 Resource 상태를 변경하면 200 OK를 반환한다")
    void changeStatus_WithAdminRole_ShouldReturnOk() throws Exception {
        Resource resource = persistResourceWithPolicy("Admin Status Target " + System.nanoTime());

        mockMvc.perform(patch("/api/admin/resources/{resourceId}/status", resource.getId())
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 권한으로 Resource 상태 변경을 요청하면 403 Forbidden을 반환한다")
    void changeStatus_WithUserRole_ShouldReturnForbidden() throws Exception {
        Resource resource = persistResourceWithPolicy("User Status Target " + System.nanoTime());

        mockMvc.perform(patch("/api/admin/resources/{resourceId}/status", resource.getId())
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰 없이 Resource 상태 변경을 요청하면 401 Unauthorized를 반환한다")
    void changeStatus_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        Resource resource = persistResourceWithPolicy("No Token Status Target " + System.nanoTime());

        mockMvc.perform(patch("/api/admin/resources/{resourceId}/status", resource.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "MAINTENANCE"))))
                .andExpect(status().isUnauthorized());
    }
}
