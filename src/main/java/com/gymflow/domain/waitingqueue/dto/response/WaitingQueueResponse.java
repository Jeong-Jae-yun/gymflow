package com.gymflow.domain.waitingqueue.dto.response;

import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;

import java.time.LocalDateTime;

public record WaitingQueueResponse(
        Long waitingQueueId,
        Long resourceId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        WaitingQueueStatus status,
        LocalDateTime createdAt
) {
}
