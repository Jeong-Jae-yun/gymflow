package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockRepository;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.redis.ResourceAvailabilityLockRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionLockRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionOfferedRepository;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueEventPublisher;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueuePromotedEvent;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRedisRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import com.gymflow.global.common.transaction.TransactionAwareLockReleaser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionProcessor {

    private static final Duration MAX_OFFERED_TTL = Duration.ofMinutes(2);

    private final ResourceRepository resourceRepository;
    private final ResourceAvailabilityLockRepository resourceAvailabilityLockRepository;
    private final PromotionLockRepository promotionLockRepository;
    private final ReservationSlotLockRepository reservationSlotLockRepository;
    private final WaitingQueuePromotionRepository promotionRepository;
    private final WaitingQueueRepository waitingQueueRepository;
    private final WaitingQueueRedisRepository waitingQueueRedisRepository;
    private final PromotionOfferedRepository promotionOfferedRepository;
    private final WaitingQueueEventPublisher waitingQueueEventPublisher;
    private final TransactionAwareLockReleaser lockReleaser;

    @Transactional
    public void tryPromote(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        String availabilityLockToken = resourceAvailabilityLockRepository.tryLock(resourceId).orElse(null);
        if (availabilityLockToken == null) {
            log.warn("Resource Availability Lock 획득 실패로 승급을 건너뜁니다. resourceId={}, startAt={}, endAt={}",
                    resourceId, startAt, endAt);
            return;
        }

        boolean releaseAvailabilityImmediately = true;
        try {
            Resource resource = resourceRepository.findWithReservationPolicyById(resourceId).orElse(null);
            if (resource == null || resource.getReservationPolicy() == null) {
                log.warn("ReservationPolicy가 없어 승급을 건너뜁니다. resourceId={}", resourceId);
                return;
            }
            if (resource.getStatus() != ResourceStatus.ACTIVE) {
                log.warn("Resource가 ACTIVE 상태가 아니어서 승급을 건너뜁니다. resourceId={}, status={}",
                        resourceId, resource.getStatus());
                return;
            }
            int minDuration = resource.getReservationPolicy().getMinDuration();

            String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt).orElse(null);
            if (lockToken == null) {
                log.warn("Promotion Lock 획득 실패로 승급을 건너뜁니다. resourceId={}, startAt={}, endAt={}",
                        resourceId, startAt, endAt);
                return;
            }
            boolean releasePromotionImmediately = true;
            try {
                if (promotionRepository.existsOverlapping(resourceId, PromotionStatus.OFFERED, startAt, endAt)) {
                    return;
                }

                WaitingQueue candidate = pollNextWaitingCandidate(resourceId, startAt);
                if (candidate == null) {
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                Duration remaining = Duration.between(now, endAt);
                Duration available = remaining.minus(Duration.ofMinutes(minDuration));
                if (!available.isPositive()) {
                    return;
                }
                Duration ttl = available.compareTo(MAX_OFFERED_TTL) < 0 ? available : MAX_OFFERED_TTL;

                Optional<ReservationSlotLockHandle> slotLockHandle =
                        reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt);
                if (slotLockHandle.isEmpty()) {
                    log.warn("Reservation Slot Lock 획득 실패로 승급을 건너뜁니다. resourceId={}, startAt={}, endAt={}",
                            resourceId, startAt, endAt);
                    return;
                }

                releasePromotionImmediately = false;
                releaseAvailabilityImmediately = false;
                Runnable unlockAction = () -> {
                    reservationSlotLockRepository.unlockAll(slotLockHandle.get());
                    promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
                    resourceAvailabilityLockRepository.unlock(resourceId, availabilityLockToken);
                };
                boolean deferred = lockReleaser.register(unlockAction);
                try {
                    candidate.promote();

                    WaitingQueuePromotion promotion = WaitingQueuePromotion.builder()
                            .waitingQueue(candidate)
                            .user(candidate.getUser())
                            .resource(resource)
                            .startAt(startAt)
                            .endAt(endAt)
                            .offeredAt(now)
                            .expiresAt(now.plus(ttl))
                            .build();
                    WaitingQueuePromotion saved = promotionRepository.save(promotion);

                    removeFromWaitingQueueRedis(candidate.getId(), resourceId, startAt);
                    registerOfferedTtl(saved.getId(), ttl);
                    publishPromotedEvent(saved, now);
                } finally {
                    if (!deferred) {
                        unlockAction.run();
                    }
                }
            } finally {
                if (releasePromotionImmediately) {
                    promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
                }
            }
        } finally {
            if (releaseAvailabilityImmediately) {
                resourceAvailabilityLockRepository.unlock(resourceId, availabilityLockToken);
            }
        }
    }

    private WaitingQueue pollNextWaitingCandidate(Long resourceId, LocalDateTime startAt) {
        List<Long> waitingQueueIds;
        try {
            waitingQueueIds = waitingQueueRedisRepository.findAll(resourceId, startAt);
        } catch (RuntimeException e) {
            log.warn("Redis 대기열 조회에 실패해 이번 승급 시도를 건너뜁니다. resourceId={}, startAt={}",
                    resourceId, startAt, e);
            return null;
        }
        for (Long waitingQueueId : waitingQueueIds) {
            Optional<WaitingQueue> found = waitingQueueRepository.findById(waitingQueueId);
            if (found.isPresent() && found.get().getStatus() == WaitingQueueStatus.WAITING) {
                return found.get();
            }
            removeFromWaitingQueueRedis(waitingQueueId, resourceId, startAt);
        }
        return null;
    }

    private void removeFromWaitingQueueRedis(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        try {
            waitingQueueRedisRepository.remove(waitingQueueId, resourceId, startAt);
        } catch (RuntimeException e) {
            log.error("Redis 대기열 제거에 실패했습니다. waitingQueueId={}", waitingQueueId, e);
        }
    }

    private void registerOfferedTtl(Long promotionId, Duration ttl) {
        try {
            promotionOfferedRepository.register(promotionId, ttl);
        } catch (RuntimeException e) {
            log.warn("Promotion OFFERED TTL 등록에 실패했습니다. promotionId={}", promotionId, e);
        }
    }

    private void publishPromotedEvent(WaitingQueuePromotion promotion, LocalDateTime promotedAt) {
        WaitingQueuePromotedEvent event = new WaitingQueuePromotedEvent(
                promotion.getId(),
                promotion.getWaitingQueue().getId(),
                promotion.getUser().getId(),
                promotion.getResource().getId(),
                promotion.getStartAt(),
                promotion.getEndAt(),
                promotedAt);
        try {
            waitingQueueEventPublisher.publish(event);
        } catch (RuntimeException e) {
            log.warn("WaitingQueue 승급 이벤트 발행에 실패했습니다. waitingQueueId={}", promotion.getWaitingQueue().getId(), e);
        }
    }
}
