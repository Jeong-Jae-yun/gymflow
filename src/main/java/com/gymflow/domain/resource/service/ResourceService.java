package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.response.ResourceResponse;
import com.gymflow.domain.resource.mapper.ResourceMapper;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResourceService {

    private static final Duration RESOURCE_CACHE_TTL = Duration.ofMinutes(10);

    private final ResourceRepository resourceRepository;
    private final ResourceCacheRepository resourceCacheRepository;

    public Page<ResourceResponse> getResources(Pageable pageable) {
        return resourceRepository.findAll(pageable)
                .map(ResourceMapper::toResponse);
    }

    public ResourceResponse getResourceDetail(Long resourceId) {
        Optional<ResourceResponse> cached = getFromCache(resourceId);
        if (cached.isPresent()) {
            log.debug("Resource cache hit. resourceId={}", resourceId);
            return cached.get();
        }
        log.debug("Resource cache miss. resourceId={}", resourceId);

        Resource resource = resourceRepository.findWithReservationPolicyById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        ResourceResponse response = ResourceMapper.toResponse(resource);
        putToCache(resourceId, response);

        return response;
    }

    private Optional<ResourceResponse> getFromCache(Long resourceId) {
        try {
            return resourceCacheRepository.get(resourceId);
        } catch (RuntimeException e) {
            log.warn("Resource cache operation failed. resourceId={}", resourceId, e);
            return Optional.empty();
        }
    }

    private void putToCache(Long resourceId, ResourceResponse response) {
        try {
            resourceCacheRepository.set(resourceId, response, RESOURCE_CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("Resource cache operation failed. resourceId={}", resourceId, e);
        }
    }
}
