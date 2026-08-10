package com.gymflow.domain.user.service;

import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.user.dto.request.LoginRequest;
import com.gymflow.domain.user.dto.response.LoginResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.jwt.JwtProperties;
import com.gymflow.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString("test-jwt-secret-key-for-unit-test-must-be-long-enough".getBytes());

    @Mock
    private UserRepository userRepository;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Spy
    private JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(new JwtProperties(TEST_SECRET, 3_600_000L));

    @InjectMocks
    private AuthService authService;

    private User activeUser(String rawPassword) {
        User user = User.builder()
                .email("test@gymflow.com")
                .password(passwordEncoder.encode(rawPassword))
                .name("John Doe")
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    @Test
    @DisplayName("정상적인 이메일/비밀번호로 로그인하면 Access Token을 발급한다")
    void login_WithValidCredentials_ShouldReturnAccessToken() {
        // given
        User user = activeUser("securePassword123");
        LoginRequest request = new LoginRequest("test@gymflow.com", "securePassword123");
        when(userRepository.findByEmailAndDeletedAtIsNull(request.email())).thenReturn(Optional.of(user));

        // when
        LoginResponse response = authService.login(request);

        // then
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(jwtTokenProvider.validateToken(response.accessToken())).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인하면 실패한다")
    void login_WithNonExistentEmail_ShouldThrowBusinessException() {
        // given
        LoginRequest request = new LoginRequest("notfound@gymflow.com", "securePassword123");
        when(userRepository.findByEmailAndDeletedAtIsNull(request.email())).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인하면 실패한다")
    void login_WithWrongPassword_ShouldThrowBusinessException() {
        // given
        User user = activeUser("securePassword123");
        LoginRequest request = new LoginRequest("test@gymflow.com", "wrongPassword123");
        when(userRepository.findByEmailAndDeletedAtIsNull(request.email())).thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("INACTIVE 상태의 사용자는 로그인할 수 없다")
    void login_WithInactiveUser_ShouldThrowBusinessException() {
        // given
        User user = activeUser("securePassword123");
        ReflectionTestUtils.setField(user, "status", com.gymflow.domain.user.domain.enumtype.UserStatus.INACTIVE);
        LoginRequest request = new LoginRequest("test@gymflow.com", "securePassword123");
        when(userRepository.findByEmailAndDeletedAtIsNull(request.email())).thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INACTIVE_USER);
    }
}
