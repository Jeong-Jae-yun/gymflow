package com.gymflow.domain.user.service;

import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserStatus;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.user.dto.request.LoginRequest;
import com.gymflow.domain.user.dto.response.LoginResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INACTIVE_USER);
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getRole());
        return LoginResponse.of(accessToken);
    }
}
