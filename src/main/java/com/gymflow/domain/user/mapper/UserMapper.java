package com.gymflow.domain.user.mapper;

import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.dto.response.UserResponse;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
