package com.gymflow.domain.favorite.service;

import com.gymflow.domain.favorite.domain.entity.Favorite;
import com.gymflow.domain.favorite.domain.repository.FavoriteRepository;
import com.gymflow.domain.favorite.dto.response.FavoriteResponse;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    private static final Long CURRENT_USER_ID = 1L;
    private static final Long RESOURCE_ID = 10L;

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FavoriteService favoriteService;

    @BeforeEach
    void setUpSecurityContext() {
        CustomUserDetails principal = new CustomUserDetails(CURRENT_USER_ID, "test@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Resource activeResource() {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", RESOURCE_ID);
        return resource;
    }

    private User user() {
        User user = User.builder()
                .email("test@gymflow.com")
                .password("securePassword123")
                .name("John Doe")
                .build();
        ReflectionTestUtils.setField(user, "id", CURRENT_USER_ID);
        return user;
    }

    private Favorite favorite(Long id, User user, Resource resource) {
        Favorite favorite = Favorite.builder()
                .user(user)
                .resource(resource)
                .build();
        ReflectionTestUtils.setField(favorite, "id", id);
        return favorite;
    }

    private void stubSave() {
        when(favoriteRepository.save(any(Favorite.class))).thenAnswer(invocation -> {
            Favorite favorite = invocation.getArgument(0);
            ReflectionTestUtils.setField(favorite, "id", 100L);
            return favorite;
        });
    }

    @Test
    @DisplayName("정상적인 요청이면 Favorite를 생성하고 FavoriteResponse를 반환한다")
    void addFavorite_WithValidRequest_ShouldSucceed() {
        // given
        Resource resource = activeResource();
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(favoriteRepository.existsByUserIdAndResourceId(CURRENT_USER_ID, RESOURCE_ID)).thenReturn(false);
        when(userRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(user());
        stubSave();

        // when
        FavoriteResponse response = favoriteService.addFavorite(RESOURCE_ID);

        // then
        assertThat(response.favoriteId()).isEqualTo(100L);
        assertThat(response.resourceId()).isEqualTo(RESOURCE_ID);
    }

    @Test
    @DisplayName("존재하지 않는 Resource를 즐겨찾기하면 예외가 발생한다")
    void addFavorite_WithNonExistentResource_ShouldThrowException() {
        // given
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> favoriteService.addFavorite(RESOURCE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(favoriteRepository, never()).save(any(Favorite.class));
    }

    @Test
    @DisplayName("ACTIVE 상태가 아닌 Resource를 즐겨찾기하면 예외가 발생한다")
    void addFavorite_WithInactiveResource_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        ReflectionTestUtils.setField(resource, "status", ResourceStatus.INACTIVE);
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> favoriteService.addFavorite(RESOURCE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_ACTIVE);

        verify(favoriteRepository, never()).save(any(Favorite.class));
    }

    @Test
    @DisplayName("이미 즐겨찾기한 Resource를 다시 즐겨찾기하면 예외가 발생한다")
    void addFavorite_WithAlreadyFavoritedResource_ShouldThrowException() {
        // given
        Resource resource = activeResource();
        when(resourceRepository.findById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(favoriteRepository.existsByUserIdAndResourceId(CURRENT_USER_ID, RESOURCE_ID)).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> favoriteService.addFavorite(RESOURCE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FAVORITE_ALREADY_EXISTS);

        verify(favoriteRepository, never()).save(any(Favorite.class));
    }

    @Test
    @DisplayName("본인의 즐겨찾기는 정상적으로 삭제된다")
    void removeFavorite_WithOwnedFavorite_ShouldSucceed() {
        // given
        Resource resource = activeResource();
        Favorite favorite = favorite(100L, user(), resource);
        when(favoriteRepository.findByUserIdAndResourceId(CURRENT_USER_ID, RESOURCE_ID))
                .thenReturn(Optional.of(favorite));

        // when
        favoriteService.removeFavorite(RESOURCE_ID);

        // then
        ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
        verify(favoriteRepository).delete(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("다른 사용자의 즐겨찾기는 삭제할 수 없다")
    void removeFavorite_WithOtherUsersFavorite_ShouldThrowException() {
        // given
        // findByUserIdAndResourceId가 이미 소유자 기준으로 필터링하므로 타인의 즐겨찾기는 조회되지 않는다
        when(favoriteRepository.findByUserIdAndResourceId(CURRENT_USER_ID, RESOURCE_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> favoriteService.removeFavorite(RESOURCE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FAVORITE_NOT_FOUND);

        verify(favoriteRepository, never()).delete(any(Favorite.class));
    }

    @Test
    @DisplayName("존재하지 않는 즐겨찾기를 삭제하면 예외가 발생한다")
    void removeFavorite_WithNonExistentFavorite_ShouldThrowException() {
        // given
        when(favoriteRepository.findByUserIdAndResourceId(CURRENT_USER_ID, RESOURCE_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> favoriteService.removeFavorite(RESOURCE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FAVORITE_NOT_FOUND);
    }

    @Test
    @DisplayName("현재 로그인한 사용자의 즐겨찾기 목록만 반환한다")
    void getMyFavorites_ShouldReturnOnlyCurrentUsersFavorites() {
        // given
        Resource resource = activeResource();
        Favorite favorite = favorite(100L, user(), resource);
        when(favoriteRepository.findAllByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID))
                .thenReturn(List.of(favorite));

        // when
        List<FavoriteResponse> response = favoriteService.getMyFavorites();

        // then
        assertThat(response).hasSize(1);
        assertThat(response.get(0).favoriteId()).isEqualTo(100L);
        assertThat(response.get(0).resourceId()).isEqualTo(RESOURCE_ID);
        verify(favoriteRepository).findAllByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID);
    }

    @Test
    @DisplayName("즐겨찾기 목록은 Repository가 반환한 createdAt DESC 순서를 그대로 유지한다")
    void getMyFavorites_ShouldPreserveCreatedAtDescOrderFromRepository() {
        // given
        Resource resource = activeResource();
        User user = user();
        Favorite newest = favorite(102L, user, resource);
        ReflectionTestUtils.setField(newest, "createdAt", LocalDateTime.of(2026, 8, 12, 12, 0));
        Favorite oldest = favorite(101L, user, resource);
        ReflectionTestUtils.setField(oldest, "createdAt", LocalDateTime.of(2026, 8, 10, 9, 0));
        when(favoriteRepository.findAllByUserIdOrderByCreatedAtDesc(CURRENT_USER_ID))
                .thenReturn(List.of(newest, oldest));

        // when
        List<FavoriteResponse> response = favoriteService.getMyFavorites();

        // then
        assertThat(response).extracting(FavoriteResponse::favoriteId)
                .containsExactly(102L, 101L);
    }
}
