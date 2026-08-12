package com.gymflow.domain.favorite.domain.entity;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FavoriteTest {

    private User createUser() {
        return User.builder()
                .email("test@gymflow.com")
                .password("securePassword123")
                .name("John Doe")
                .build();
    }

    private Resource createResource() {
        return Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
    }

    @Test
    @DisplayName("Favorite는 User와 Resource로 정상 생성된다")
    void createFavorite_WithUserAndResource_ShouldSucceed() {
        // given
        User user = createUser();
        Resource resource = createResource();

        // when
        Favorite favorite = Favorite.builder()
                .user(user)
                .resource(resource)
                .build();

        // then
        assertThat(favorite.getUser()).isSameAs(user);
        assertThat(favorite.getResource()).isSameAs(resource);
    }

    @Test
    @DisplayName("user가 null이면 Favorite 생성에 실패한다")
    void createFavorite_WithNullUser_ShouldThrowException() {
        assertThatThrownBy(() -> Favorite.builder()
                .user(null)
                .resource(createResource())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User");
    }

    @Test
    @DisplayName("resource가 null이면 Favorite 생성에 실패한다")
    void createFavorite_WithNullResource_ShouldThrowException() {
        assertThatThrownBy(() -> Favorite.builder()
                .user(createUser())
                .resource(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource");
    }
}
