package com.gymflow.domain.resource.dto.request;

import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminResourceCreateRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        ResourceType type,

        @NotNull
        @Min(1)
        Integer capacity,

        @Size(max = 500)
        String description,

        @NotNull
        @Positive
        Integer slotDuration,

        @NotNull
        @Positive
        Integer minDuration,

        @NotNull
        @Positive
        Integer maxDuration
) {
}
