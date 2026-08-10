package com.gymflow.global.security;

import com.gymflow.TestcontainersConfiguration;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("회원가입 API는 인증 없이 접근할 수 있다")
    void signUp_WithoutToken_ShouldBeAccessible() throws Exception {
        Map<String, String> request = Map.of(
                "email", "security-" + UUID.randomUUID() + "@gymflow.com",
                "password", "securePassword123",
                "name", "John Doe"
        );

        mockMvc.perform(post("/api/v1/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("로그인 API는 인증 없이 접근할 수 있다")
    void login_WithoutToken_ShouldBeAccessible() throws Exception {
        Map<String, String> request = Map.of(
                "email", "nonexistent@gymflow.com",
                "password", "securePassword123"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증이 필요한 API에 토큰 없이 접근하면 401을 반환한다")
    void accessProtectedApi_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/test/authenticated"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 토큰으로 인증이 필요한 API에 접근하면 정상 처리된다")
    void accessProtectedApi_WithValidToken_ShouldBeAccessible() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, "test@gymflow.com", UserRole.USER);

        mockMvc.perform(get("/api/v1/test/authenticated")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 권한으로 ADMIN 전용 API에 접근하면 403을 반환한다")
    void accessAdminApi_WithUserRole_ShouldReturnForbidden() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, "user@gymflow.com", UserRole.USER);

        mockMvc.perform(get("/api/v1/test/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 권한으로 ADMIN 전용 API에 접근하면 정상 처리된다")
    void accessAdminApi_WithAdminRole_ShouldBeAccessible() throws Exception {
        String token = jwtTokenProvider.createAccessToken(2L, "admin@gymflow.com", UserRole.ADMIN);

        mockMvc.perform(get("/api/v1/test/admin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
