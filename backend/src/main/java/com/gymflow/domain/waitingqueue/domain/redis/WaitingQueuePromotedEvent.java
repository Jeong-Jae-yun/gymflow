package com.gymflow.domain.waitingqueue.domain.redis;

import java.time.LocalDateTime;

public record WaitingQueuePromotedEvent(
        Long promotionId,
        Long waitingQueueId,
        Long userId,
        Long resourceId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime promotedAt
) {
}
