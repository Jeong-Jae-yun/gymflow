package com.gymflow.domain.user.controller;

import tools.jackson.databind.ObjectMapper;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.enumtype.UserStatus;
import com.gymflow.domain.user.dto.request.UserSignUpRequest;
import com.gymflow.domain.user.dto.response.UserResponse;
import com.gymflow.domain.user.service.UserService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @Test
    @DisplayName("정상적인 회원가입 요청은 201 Created와 UserResponse를 반환한다")
    void signUp_WithValidRequest_ShouldReturnCreated() throws Exception {
        // given
        UserSignUpRequest request = new UserSignUpRequest("test@gymflow.com", "securePassword123", "John Doe");
        UserResponse response = new UserResponse(
                1L, request.email(), request.name(), UserRole.USER, UserStatus.ACTIVE, LocalDateTime.now());
        when(userService.signUp(any(UserSignUpRequest.class))).thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/v1/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.email").value(request.email()))
                .andExpect(jsonPath("$.name").value(request.name()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(userService).signUp(any(UserSignUpRequest.class));
    }

    @Test
    @DisplayName("필수 값이 비어 있으면 400 Bad Request를 반환하고 Service는 호출되지 않는다")
    void signUp_WithInvalidRequest_ShouldReturnBadRequest() throws Exception {
        // given
        String invalidRequestJson = """
                {"email": "", "password": "securePassword123", "name": "John Doe"}
                """;

        // when & then
        mockMvc.perform(post("/api/v1/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequestJson))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("이메일이 이미 사용 중이면 409 Conflict를 반환한다")
    void signUp_WithDuplicateEmail_ShouldReturnConflict() throws Exception {
        // given
        UserSignUpRequest request = new UserSignUpRequest("test@gymflow.com", "securePassword123", "John Doe");
        when(userService.signUp(any(UserSignUpRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        // when & then
        mockMvc.perform(post("/api/v1/users/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_EMAIL.getMessage()));
    }
}
