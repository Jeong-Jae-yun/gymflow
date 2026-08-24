package com.gymflow.domain.favorite.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.favorite.domain.entity.Favorite;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class FavoriteRepositoryTest {

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String email) {
        User user = User.builder()
                .email(email)
                .password("securePassword123")
                .name("Favorite Tester")
                .build();
        entityManager.persist(user);
        return user;
    }

    private Resource persistResource(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        entityManager.persist(resource);
        return resource;
    }

    @Test
    @DisplayName("Favorite는 User/Resource와 연결되어 실제 DB에 저장된다")
    void save_ShouldPersistFavoriteWithAssociations() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        Favorite favorite = Favorite.builder().user(user).resource(resource).build();

        // when
        Favorite saved = favoriteRepository.save(favorite);
        entityManager.flush();
        entityManager.clear();

        // then
        Favorite reloaded = favoriteRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getResource().getId()).isEqualTo(resource.getId());
    }

    @Test
    @DisplayName("동일한 User와 Resource로 중복 저장하면 UNIQUE 제약을 위반한다")
    void save_WithDuplicateUserAndResource_ShouldViolateUniqueConstraint() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        favoriteRepository.save(Favorite.builder().user(user).resource(resource).build());
        entityManager.flush();

        Favorite duplicate = Favorite.builder().user(user).resource(resource).build();

        // when & then
        assertThatThrownBy(() -> {
            favoriteRepository.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("existsByUserIdAndResourceId는 이미 즐겨찾기한 경우 true를 반환한다")
    void existsByUserIdAndResourceId_WithExistingFavorite_ShouldReturnTrue() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        favoriteRepository.save(Favorite.builder().user(user).resource(resource).build());
        entityManager.flush();

        // when
        boolean exists = favoriteRepository.existsByUserIdAndResourceId(user.getId(), resource.getId());

        // then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("findByUserIdAndResourceId는 다른 사용자의 즐겨찾기를 조회하면 빈 값을 반환한다")
    void findByUserIdAndResourceId_WithOtherUser_ShouldReturnEmpty() {
        // given
        User owner = persistUser("owner@gymflow.com");
        User other = persistUser("other@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        favoriteRepository.save(Favorite.builder().user(owner).resource(resource).build());
        entityManager.flush();

        // when
        Optional<Favorite> found = favoriteRepository.findByUserIdAndResourceId(other.getId(), resource.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findAllByUserIdOrderByCreatedAtDesc는 현재 사용자의 즐겨찾기만 최신순으로 반환한다")
    void findAllByUserIdOrderByCreatedAtDesc_ShouldReturnOnlyOwnersFavoritesInDescOrder() {
        // given
        User owner = persistUser("owner@gymflow.com");
        User other = persistUser("other@gymflow.com");
        Resource resourceA = persistResource("Chest Press A-1");
        Resource resourceB = persistResource("Chest Press A-2");
        Resource resourceC = persistResource("Chest Press A-3");

        Favorite first = favoriteRepository.save(Favorite.builder().user(owner).resource(resourceA).build());
        entityManager.flush();
        Favorite second = favoriteRepository.save(Favorite.builder().user(owner).resource(resourceB).build());
        entityManager.flush();
        favoriteRepository.save(Favorite.builder().user(other).resource(resourceC).build());
        entityManager.flush();

        // when
        List<Favorite> favorites = favoriteRepository.findAllByUserIdOrderByCreatedAtDesc(owner.getId());

        // then
        assertThat(favorites).extracting(Favorite::getId)
                .containsExactly(second.getId(), first.getId());
    }
}
