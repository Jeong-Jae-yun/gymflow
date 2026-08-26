package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository.RankedResource;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.response.PopularResourceResponse;
import com.gymflow.domain.resource.dto.response.ResourceRankingResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResourceService {

    private static final Duration RESOURCE_CACHE_TTL = Duration.ofMinutes(10);
    private static final int RANKING_SCAN_BATCH_SIZE = 20;

    private final ResourceRepository resourceRepository;
    private final ResourceCacheRepository resourceCacheRepository;
    private final ResourceRankingRedisRepository resourceRankingRedisRepository;

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

    public List<PopularResourceResponse> getPopularResources(int limit) {
        List<RankedResource> rankedResources = getTopResourcesFromRanking(limit);
        if (rankedResources.isEmpty()) {
            return List.of();
        }

        List<Long> resourceIds = rankedResources.stream().map(RankedResource::resourceId).toList();
        Set<Long> activeResourceIds = resourceRepository.findAllById(resourceIds).stream()
                .filter(resource -> resource.getStatus() == ResourceStatus.ACTIVE)
                .map(Resource::getId)
                .collect(Collectors.toSet());

        List<PopularResourceResponse> popularResources = new ArrayList<>();
        for (int i = 0; i < rankedResources.size(); i++) {
            RankedResource ranked = rankedResources.get(i);
            if (activeResourceIds.contains(ranked.resourceId())) {
                popularResources.add(new PopularResourceResponse(ranked.resourceId(), ranked.score(), i + 1));
            }
        }
        return popularResources;
    }

    private List<RankedResource> getTopResourcesFromRanking(int limit) {
        try {
            return resourceRankingRedisRepository.findTopResources(limit);
        } catch (RuntimeException e) {
            log.warn("Resource ranking operation failed.", e);
            return List.of();
        }
    }

    public List<ResourceRankingResponse> getTopRankings(int limit) {
        try {
            return scanTopRankings(limit);
        } catch (RankingUnavailableException e) {
            log.warn("Resource ranking scan failed.", e.getCause());
            return List.of();
        }
    }

    public ResourceRankingResponse getResourceRanking(Long resourceId) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        try {
            return resolveRanking(resource);
        } catch (RankingUnavailableException e) {
            log.warn("Resource ranking lookup failed. resourceId={}", resourceId, e.getCause());
            return toRankingResponse(resource, null, 0L);
        }
    }

    private List<ResourceRankingResponse> scanTopRankings(int limit) {
        List<ResourceRankingResponse> result = new ArrayList<>();
        long offset = 0;
        long activeRank = 1;

        while (result.size() < limit) {
            List<RankedResource> candidates = findTopResourcesOrThrow(offset);
            if (candidates.isEmpty()) {
                break;
            }

            Map<Long, Resource> resourceMap = findResourceMap(candidates);
            for (RankedResource candidate : candidates) {
                Resource resource = resourceMap.get(candidate.resourceId());
                if (resource == null || resource.getStatus() != ResourceStatus.ACTIVE) {
                    continue;
                }
                result.add(toRankingResponse(resource, activeRank++, candidate.score()));
                if (result.size() == limit) {
                    break;
                }
            }

            if (candidates.size() < RANKING_SCAN_BATCH_SIZE) {
                break;
            }
            offset += RANKING_SCAN_BATCH_SIZE;
        }
        return result;
    }

    private ResourceRankingResponse resolveRanking(Resource resource) {
        Long resourceId = resource.getId();
        Optional<Long> score = findScoreOrThrow(resourceId);

        if (resource.getStatus() != ResourceStatus.ACTIVE) {
            return toRankingResponse(resource, null, score.orElse(0L));
        }
        if (score.isEmpty()) {
            return toRankingResponse(resource, null, 0L);
        }

        Long rank = calculateActiveRank(resourceId);
        return toRankingResponse(resource, rank, score.get());
    }

    private Long calculateActiveRank(Long targetResourceId) {
        long offset = 0;
        long activeRank = 0;

        while (true) {
            List<RankedResource> candidates = findTopResourcesOrThrow(offset);
            if (candidates.isEmpty()) {
                return null;
            }

            Map<Long, Resource> resourceMap = findResourceMap(candidates);
            for (RankedResource candidate : candidates) {
                Resource resource = resourceMap.get(candidate.resourceId());
                if (resource == null || resource.getStatus() != ResourceStatus.ACTIVE) {
                    continue;
                }
                activeRank++;
                if (candidate.resourceId().equals(targetResourceId)) {
                    return activeRank;
                }
            }

            if (candidates.size() < RANKING_SCAN_BATCH_SIZE) {
                return null;
            }
            offset += RANKING_SCAN_BATCH_SIZE;
        }
    }

    private Map<Long, Resource> findResourceMap(List<RankedResource> candidates) {
        List<Long> ids = candidates.stream().map(RankedResource::resourceId).toList();
        return resourceRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));
    }

    private List<RankedResource> findTopResourcesOrThrow(long offset) {
        try {
            return resourceRankingRedisRepository.findTopResources(offset, RANKING_SCAN_BATCH_SIZE);
        } catch (RuntimeException e) {
            throw new RankingUnavailableException(e);
        }
    }

    private Optional<Long> findScoreOrThrow(Long resourceId) {
        try {
            return resourceRankingRedisRepository.findScore(resourceId);
        } catch (RuntimeException e) {
            throw new RankingUnavailableException(e);
        }
    }

    private ResourceRankingResponse toRankingResponse(Resource resource, Long rank, Long score) {
        return new ResourceRankingResponse(
                rank,
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getStatus(),
                score
        );
    }

    private static final class RankingUnavailableException extends RuntimeException {
        RankingUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
