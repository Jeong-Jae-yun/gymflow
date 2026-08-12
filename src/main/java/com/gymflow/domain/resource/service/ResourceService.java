package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.response.ResourceResponse;
import com.gymflow.domain.resource.mapper.ResourceMapper;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResourceService {

    private final ResourceRepository resourceRepository;

    public Page<ResourceResponse> getResources(Pageable pageable) {
        return resourceRepository.findAll(pageable)
                .map(ResourceMapper::toResponse);
    }

    public ResourceResponse getResourceDetail(Long resourceId) {
        Resource resource = resourceRepository.findWithReservationPolicyById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        return ResourceMapper.toResponse(resource);
    }
}
