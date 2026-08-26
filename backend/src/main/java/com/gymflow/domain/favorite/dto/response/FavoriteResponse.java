package com.gymflow.domain.favorite.dto.response;

import java.time.LocalDateTime;

public record FavoriteResponse(
        Long favoriteId,
        Long resourceId,
        LocalDateTime createdAt
) {
}
