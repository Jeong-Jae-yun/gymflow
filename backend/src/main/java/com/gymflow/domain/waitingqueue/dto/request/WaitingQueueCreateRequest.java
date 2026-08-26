package com.gymflow.domain.waitingqueue.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record WaitingQueueCreateRequest(

        @NotNull
        Long resourceId,

        @NotNull
        LocalDateTime startAt,

        @NotNull
        LocalDateTime endAt
) {
}
