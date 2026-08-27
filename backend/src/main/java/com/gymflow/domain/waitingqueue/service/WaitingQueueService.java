package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRedisRepository;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRegistrationLockRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;
import com.gymflow.domain.waitingqueue.mapper.WaitingQueueMapper;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.common.transaction.TransactionAwareLockReleaser;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WaitingQueueService {

    private final WaitingQueueRepository waitingQueueRepository;
    private final ResourceRepository resourceRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final WaitingQueueRedisRepository waitingQueueRedisRepository;
    private final WaitingQueueRegistrationLockRepository waitingQueueRegistrationLockRepository;
    private final WaitingQueuePromotionRepository waitingQueuePromotionRepository;
    private final TransactionAwareLockReleaser lockReleaser;

    @Transactional
    public WaitingQueueResponse registerWaitingQueue(WaitingQueueCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Resource resource = resourceRepository.findById(request.resourceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (resource.getStatus() != ResourceStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_ACTIVE);
        }

        LocalDateTime startAt = request.startAt();
        LocalDateTime endAt = request.endAt();
        if (!startAt.isBefore(endAt)) {
            throw new BusinessException(ErrorCode.INVALID_WAITING_QUEUE_TIME_RANGE);
        }

        boolean reservationAvailable = !reservationRepository.existsOverlapping(
                resource.getId(), ReservationStatus.OCCUPYING_STATUSES, startAt, endAt);
        if (reservationAvailable) {
            throw new BusinessException(ErrorCode.WAITING_QUEUE_NOT_AVAILABLE);
        }

        String lockToken = waitingQueueRegistrationLockRepository
                .tryLock(currentUserId, resource.getId(), startAt, endAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_QUEUE_IN_PROGRESS));
        Runnable unlockAction = () ->
                waitingQueueRegistrationLockRepository.unlock(currentUserId, resource.getId(), startAt, endAt, lockToken);
        boolean deferred = lockReleaser.register(unlockAction);
        try {
            boolean alreadyWaiting = waitingQueueRepository.existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
                    currentUserId, resource.getId(), startAt, endAt, WaitingQueueStatus.WAITING);
            if (alreadyWaiting) {
                throw new BusinessException(ErrorCode.WAITING_QUEUE_ALREADY_EXISTS);
            }

            User user = userRepository.getReferenceById(currentUserId);

            WaitingQueue waitingQueue = WaitingQueue.builder()
                    .user(user)
                    .resource(resource)
                    .startAt(startAt)
                    .endAt(endAt)
                    .build();

            WaitingQueue savedWaitingQueue = waitingQueueRepository.save(waitingQueue);

            Long waitingRank = registerToRedisAndGetRank(savedWaitingQueue);

            return WaitingQueueMapper.toResponse(savedWaitingQueue, waitingRank, null);
        } finally {
            if (!deferred) {
                unlockAction.run();
            }
        }
    }

    public Page<WaitingQueueResponse> getMyWaitingQueues(Pageable pageable) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        return waitingQueueRepository.findAllByUserId(currentUserId, pageable)
                .map(this::toResponseWithRank);
    }

    @Transactional
    public void cancelWaitingQueue(Long waitingQueueId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        WaitingQueue waitingQueue = waitingQueueRepository.findByIdAndUserId(waitingQueueId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_QUEUE_NOT_FOUND));

        if (waitingQueue.getStatus() != WaitingQueueStatus.WAITING) {
            throw new BusinessException(ErrorCode.WAITING_QUEUE_NOT_CANCELLABLE);
        }

        waitingQueue.cancel();
        removeFromRedis(waitingQueue);
    }

    private Long registerToRedisAndGetRank(WaitingQueue waitingQueue) {
        Long resourceId = waitingQueue.getResource().getId();
        LocalDateTime startAt = waitingQueue.getStartAt();
        try {
            waitingQueueRedisRepository.add(
                    waitingQueue.getId(), resourceId, startAt, waitingQueue.getCreatedAt());
            return waitingQueueRedisRepository.rank(waitingQueue.getId(), resourceId, startAt)
                    .map(rank -> rank + 1)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.error("Redis 대기열 등록에 실패했습니다. waitingQueueId={}", waitingQueue.getId(), e);
            return null;
        }
    }

    private void removeFromRedis(WaitingQueue waitingQueue) {
        try {
            waitingQueueRedisRepository.remove(
                    waitingQueue.getId(), waitingQueue.getResource().getId(), waitingQueue.getStartAt());
        } catch (RuntimeException e) {
            log.error("Redis 대기열 제거에 실패했습니다. waitingQueueId={}", waitingQueue.getId(), e);
        }
    }

    private WaitingQueueResponse toResponseWithRank(WaitingQueue waitingQueue) {
        Long waitingRank = null;
        if (waitingQueue.getStatus() == WaitingQueueStatus.WAITING) {
            try {
                waitingRank = waitingQueueRedisRepository.rank(
                                waitingQueue.getId(), waitingQueue.getResource().getId(), waitingQueue.getStartAt())
                        .map(rank -> rank + 1)
                        .orElse(null);
            } catch (RuntimeException e) {
                log.error("Redis 대기 순번 조회에 실패했습니다. waitingQueueId={}", waitingQueue.getId(), e);
            }
        }
        Long promotionId = resolvePromotionId(waitingQueue);
        return WaitingQueueMapper.toResponse(waitingQueue, waitingRank, promotionId);
    }

    private Long resolvePromotionId(WaitingQueue waitingQueue) {
        if (waitingQueue.getStatus() != WaitingQueueStatus.PROMOTED) {
            return null;
        }
        return waitingQueuePromotionRepository.findByWaitingQueueId(waitingQueue.getId())
                .map(WaitingQueuePromotion::getId)
                .orElse(null);
    }
}
