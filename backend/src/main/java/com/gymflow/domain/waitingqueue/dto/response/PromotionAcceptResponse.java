package com.gymflow.domain.waitingqueue.dto.response;

import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;

import java.time.LocalDateTime;

public record PromotionAcceptResponse(
        Long promotionId,
        PromotionStatus promotionStatus,
        Long reservationId,
        LocalDateTime checkInDeadline
) {
}
