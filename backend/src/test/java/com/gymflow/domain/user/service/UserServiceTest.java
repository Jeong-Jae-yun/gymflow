package com.gymflow.domain.user.service;

import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.user.dto.request.UserSignUpRequest;
import com.gymflow.domain.user.dto.response.UserResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long CURRENT_USER_ID = 1L;

    @Mock
    private UserRepository userRepository;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private UserService userService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId) {
        CustomUserDetails principal = new CustomUserDetails(userId, "test@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private User user(Long id) {
        User user = User.builder()
                .email("test@gymflow.com")
                .password("encodedPassword")
                .name("John Doe")
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("회원가입에 성공하면 저장된 정보로 UserResponse를 반환한다")
    void signUp_WithValidRequest_ShouldReturnUserResponse() {
        // given
        UserSignUpRequest request = new UserSignUpRequest("test@gymflow.com", "securePassword123", "John Doe");
        when(userRepository.existsByEmailAndDeletedAtIsNull(request.email())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UserResponse response = userService.signUp(request);

        // then
        assertThat(response.email()).isEqualTo(request.email());
        assertThat(response.name()).isEqualTo(request.name());
    }

    @Test
    @DisplayName("이미 사용 중인 이메일로 회원가입하면 예외가 발생한다")
    void signUp_WithDuplicateEmail_ShouldThrowBusinessException() {
        // given
        UserSignUpRequest request = new UserSignUpRequest("test@gymflow.com", "securePassword123", "John Doe");
        when(userRepository.existsByEmailAndDeletedAtIsNull(request.email())).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.signUp(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_EMAIL);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 시 비밀번호는 평문이 아닌 암호화된 값으로 저장된다")
    void signUp_ShouldEncodePasswordBeforeSaving() {
        // given
        UserSignUpRequest request = new UserSignUpRequest("test@gymflow.com", "securePassword123", "John Doe");
        when(userRepository.existsByEmailAndDeletedAtIsNull(request.email())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        userService.signUp(request);

        // then
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getPassword()).isNotEqualTo(request.password());
        assertThat(passwordEncoder.matches(request.password(), savedUser.getPassword())).isTrue();
    }

    // ===================== getMe =====================

    @Test
    @DisplayName("인증된 사용자는 자신의 UserResponse를 조회한다")
    void getMe_WithAuthenticatedUser_ShouldReturnOwnUserResponse() {
        // given
        authenticateAs(CURRENT_USER_ID);
        User user = user(CURRENT_USER_ID);
        when(userRepository.findById(CURRENT_USER_ID)).thenReturn(Optional.of(user));

        // when
        UserResponse response = userService.getMe();

        // then
        assertThat(response.id()).isEqualTo(CURRENT_USER_ID);
        assertThat(response.email()).isEqualTo("test@gymflow.com");
        assertThat(response.name()).isEqualTo("John Doe");
        assertThat(response.role()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("인증 정보에 해당하는 User가 DB에 없으면 USER_NOT_FOUND 예외가 발생한다")
    void getMe_WithNonExistentUser_ShouldThrowException() {
        // given
        authenticateAs(999L);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.getMe())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("인증 정보가 없으면 AUTHENTICATION_REQUIRED 예외가 발생한다")
    void getMe_WithoutAuthentication_ShouldThrowException() {
        // when & then
        assertThatThrownBy(() -> userService.getMe())
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED);

        verify(userRepository, never()).findById(any());
    }
}
