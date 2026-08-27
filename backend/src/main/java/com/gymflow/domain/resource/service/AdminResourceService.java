package com.gymflow.domain.resource.service;

import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.redis.ResourceAvailabilityLockRepository;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.domain.storage.ResourceImageStorage;
import com.gymflow.domain.resource.dto.request.AdminResourceCreateRequest;
import com.gymflow.domain.resource.dto.request.AdminResourceStatusUpdateRequest;
import com.gymflow.domain.resource.dto.request.AdminResourceUpdateRequest;
import com.gymflow.domain.resource.dto.response.AdminResourceResponse;
import com.gymflow.domain.resource.mapper.AdminResourceMapper;
import com.gymflow.domain.waitingqueue.service.PromotionService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.common.transaction.TransactionAwareLockReleaser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminResourceService {

    private final ResourceRepository resourceRepository;
    private final ReservationRepository reservationRepository;
    private final PromotionService promotionService;
    private final ResourceAvailabilityLockRepository resourceAvailabilityLockRepository;
    private final ResourceCacheRepository resourceCacheRepository;
    private final TransactionAwareLockReleaser lockReleaser;
    private final ResourceImageStorage resourceImageStorage;

    @Transactional
    public AdminResourceResponse createResource(AdminResourceCreateRequest request) {
        validatePolicyCombination(request.slotDuration(), request.minDuration(), request.maxDuration());

        Resource resource = Resource.builder()
                .name(request.name())
                .type(request.type())
                .capacity(request.capacity())
                .description(request.description())
                .build();

        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(request.slotDuration())
                .minDuration(request.minDuration())
                .maxDuration(request.maxDuration())
                .build();

        Resource saved = resourceRepository.save(resource);

        return AdminResourceMapper.toResponse(saved, resolveImageUrl(saved.getImageKey()));
    }

    @Transactional
    public AdminResourceResponse updateResource(Long resourceId, AdminResourceUpdateRequest request) {
        validatePolicyCombination(request.slotDuration(), request.minDuration(), request.maxDuration());

        Resource resource = resourceRepository.findWithReservationPolicyById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        resource.update(request.name(), request.capacity(), request.description());

        ReservationPolicy policy = resource.getReservationPolicy();
        if (policy == null) {
            throw new BusinessException(ErrorCode.RESERVATION_POLICY_NOT_FOUND);
        }
        policy.update(request.slotDuration(), request.minDuration(), request.maxDuration());

        evictCache(resourceId);

        return AdminResourceMapper.toResponse(resource, resolveImageUrl(resource.getImageKey()));
    }

    @Transactional
    public AdminResourceResponse changeStatus(Long resourceId, AdminResourceStatusUpdateRequest request) {
        Resource resource = resourceRepository.findWithReservationPolicyById(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        ResourceStatus newStatus = request.status();
        if (resource.getStatus() != newStatus && isOccupancyBlockingStatus(newStatus)) {
            String lockToken = resourceAvailabilityLockRepository.tryLock(resourceId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
            Runnable unlockAction = () -> resourceAvailabilityLockRepository.unlock(resourceId, lockToken);
            boolean deferred = lockReleaser.register(unlockAction);
            try {
                validateNoActiveOccupancy(resourceId);
                resource.changeStatus(newStatus);
            } finally {
                if (!deferred) {
                    unlockAction.run();
                }
            }
        } else {
            resource.changeStatus(newStatus);
        }

        evictCache(resourceId);

        return AdminResourceMapper.toResponse(resource, resolveImageUrl(resource.getImageKey()));
    }

    private String resolveImageUrl(String imageKey) {
        return imageKey == null ? null : resourceImageStorage.generateReadUrl(imageKey);
    }

    private boolean isOccupancyBlockingStatus(ResourceStatus status) {
        return status == ResourceStatus.MAINTENANCE || status == ResourceStatus.INACTIVE;
    }

    private void validateNoActiveOccupancy(Long resourceId) {
        LocalDateTime now = LocalDateTime.now();

        boolean hasOccupyingReservation = reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(
                resourceId, ReservationStatus.OCCUPYING_STATUSES, now);
        if (hasOccupyingReservation) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_CHANGE_NOT_ALLOWED);
        }

        if (promotionService.hasActiveOfferedPromotion(resourceId, now)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_CHANGE_NOT_ALLOWED);
        }
    }

    private void validatePolicyCombination(Integer slotDuration, Integer minDuration, Integer maxDuration) {
        if (maxDuration < minDuration) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_POLICY);
        }
        if (minDuration % slotDuration != 0 || maxDuration % slotDuration != 0) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_POLICY);
        }
    }

    private void evictCache(Long resourceId) {
        try {
            resourceCacheRepository.evict(resourceId);
        } catch (RuntimeException e) {
            log.warn("Resource Cache Evict에 실패했습니다. resourceId={}", resourceId, e);
        }
    }
}
