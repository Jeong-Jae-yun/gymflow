package com.gymflow.domain.user.service;

import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.user.dto.request.UserSignUpRequest;
import com.gymflow.domain.user.dto.response.UserResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private UserService userService;

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
}
