package com.gymflow.domain.waitingqueue.mapper;

import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;

public final class WaitingQueueMapper {

    private WaitingQueueMapper() {
    }

    public static WaitingQueueResponse toResponse(WaitingQueue waitingQueue, Long waitingRank, Long promotionId) {
        return new WaitingQueueResponse(
                waitingQueue.getId(),
                waitingQueue.getResource().getId(),
                waitingQueue.getStartAt(),
                waitingQueue.getEndAt(),
                waitingQueue.getStatus(),
                waitingQueue.getCreatedAt(),
                waitingRank,
                promotionId
        );
    }
}
