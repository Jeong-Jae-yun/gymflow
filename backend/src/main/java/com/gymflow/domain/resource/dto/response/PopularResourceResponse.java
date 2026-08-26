package com.gymflow.domain.resource.dto.response;

public record PopularResourceResponse(
        Long resourceId,
        Long reservationCount,
        Integer rank
) {
}
