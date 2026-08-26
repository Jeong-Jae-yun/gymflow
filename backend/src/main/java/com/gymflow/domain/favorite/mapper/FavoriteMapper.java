package com.gymflow.domain.favorite.mapper;

import com.gymflow.domain.favorite.domain.entity.Favorite;
import com.gymflow.domain.favorite.dto.response.FavoriteResponse;

public final class FavoriteMapper {

    private FavoriteMapper() {
    }

    public static FavoriteResponse toResponse(Favorite favorite) {
        return new FavoriteResponse(
                favorite.getId(),
                favorite.getResource().getId(),
                favorite.getCreatedAt()
        );
    }
}
