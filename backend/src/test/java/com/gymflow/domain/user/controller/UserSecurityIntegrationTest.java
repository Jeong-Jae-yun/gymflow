package com.gymflow.domain.user.controller;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    private User persistUser(String email) {
        User user = User.builder()
                .email(email)
                .password("securePassword123")
                .name("Security Test User")
                .build();
        return userRepository.save(user);
    }

    @Test
    @DisplayName("토큰 없이 내 정보를 조회하면 401 Unauthorized를 반환한다")
    void getMe_WithoutToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효한 토큰으로 내 정보를 조회하면 200 OK와 본인의 UserResponse를 반환한다")
    void getMe_WithValidToken_ShouldReturnOwnUserResponse() throws Exception {
        User user = persistUser("me-integration-" + System.nanoTime() + "@gymflow.com");
        String token = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), UserRole.USER);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.name").value(user.getName()))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("유효하지 않은 토큰으로 내 정보를 조회하면 401 Unauthorized를 반환한다")
    void getMe_WithInvalidToken_ShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }
}
