package com.gymflow.domain.resource.dto.request;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import jakarta.validation.constraints.NotNull;

public record AdminResourceStatusUpdateRequest(

        @NotNull
        ResourceStatus status
) {
}
