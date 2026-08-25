package com.gymflow.domain.favorite.domain.repository;

import com.gymflow.domain.favorite.domain.entity.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    boolean existsByUserIdAndResourceId(Long userId, Long resourceId);

    Optional<Favorite> findByUserIdAndResourceId(Long userId, Long resourceId);

    List<Favorite> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
