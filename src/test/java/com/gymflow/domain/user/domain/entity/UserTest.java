package com.gymflow.domain.user.domain.entity;

import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.enumtype.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    @DisplayName("User Entity should be successfully created with minimum required fields and default role and status")
    void createUser_WithRequiredFields_ShouldHaveDefaultRoleAndStatus() {
        // given
        String email = "test@gymflow.com";
        String password = "securePassword123";
        String name = "John Doe";

        // when
        User user = User.builder()
                .email(email)
                .password(password)
                .name(name)
                .build();

        // then
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getPassword()).isEqualTo(password);
        assertThat(user.getName()).isEqualTo(name);
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("User Entity should be successfully created with direct constructor call")
    void createUser_WithConstructor_ShouldHaveDefaultRoleAndStatus() {
        // given
        String email = "test@gymflow.com";
        String password = "securePassword123";
        String name = "John Doe";

        // when
        User user = new User(email, password, name);

        // then
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getPassword()).isEqualTo(password);
        assertThat(user.getName()).isEqualTo(name);
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getDeletedAt()).isNull();
    }
}
