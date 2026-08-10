package com.gymflow.domain.user.service;

import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.user.dto.request.UserSignUpRequest;
import com.gymflow.domain.user.dto.response.UserResponse;
import com.gymflow.domain.user.mapper.UserMapper;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse signUp(UserSignUpRequest request) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .build();

        User savedUser = userRepository.save(user);

        return UserMapper.toResponse(savedUser);
    }
}
