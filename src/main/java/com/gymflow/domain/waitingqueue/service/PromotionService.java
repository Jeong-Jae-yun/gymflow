package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionCheckInRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionLockRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionOfferedRepository;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueEventPublisher;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueuePromotedEvent;
import com.gymflow.domain.waitingqueue.domain.redis.WaitingQueueRedisRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import com.gymflow.domain.waitingqueue.dto.response.PromotionAcceptResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private static final Duration MAX_OFFERED_TTL = Duration.ofMinutes(2);
    private static final Duration CHECK_IN_TTL = Duration.ofMinutes(1);

    private final WaitingQueuePromotionRepository promotionRepository;
    private final WaitingQueueRepository waitingQueueRepository;
    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final WaitingQueueRedisRepository waitingQueueRedisRepository;
    private final PromotionLockRepository promotionLockRepository;
    private final ReservationSlotLockRepository reservationSlotLockRepository;
    private final PromotionOfferedRepository promotionOfferedRepository;
    private final PromotionCheckInRepository promotionCheckInRepository;
    private final WaitingQueueEventPublisher waitingQueueEventPublisher;

    public boolean hasActiveOffer(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        return promotionRepository.findByResourceIdAndStartAtAndEndAtAndStatus(
                resourceId, startAt, endAt, PromotionStatus.OFFERED).isPresent();
    }

    @Transactional
    public void tryPromote(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        Resource resource = resourceRepository.findWithReservationPolicyById(resourceId).orElse(null);
        if (resource == null || resource.getReservationPolicy() == null) {
            log.warn("ReservationPolicy가 없어 승급을 건너뜁니다. resourceId={}", resourceId);
            return;
        }
        int minDuration = resource.getReservationPolicy().getMinDuration();

        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt).orElse(null);
        if (lockToken == null) {
            log.warn("Promotion Lock 획득 실패로 승급을 건너뜁니다. resourceId={}, startAt={}, endAt={}",
                    resourceId, startAt, endAt);
            return;
        }
        try {
            if (promotionRepository.findByResourceIdAndStartAtAndEndAtAndStatus(
                    resourceId, startAt, endAt, PromotionStatus.OFFERED).isPresent()) {
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

            // Lock 획득 순서: PromotionLock(상위) → ReservationSlotLock(하위) 고정(accept()와 동일).
            // OFFERED 생성도 Resource 시간 점유권 변경이므로 createReservation/extendReservation과
            // 동일한 canonical slot 경계를 공유해야, 겹치는 시간대의 일반 예약 생성과 동시에
            // 실행되어도 둘 중 하나만 진행될 수 있다(둘 다 성립하는 상태 방지).
            Optional<ReservationSlotLockHandle> slotLockHandle =
                    reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt);
            if (slotLockHandle.isEmpty()) {
                log.warn("Reservation Slot Lock 획득 실패로 승급을 건너뜁니다. resourceId={}, startAt={}, endAt={}",
                        resourceId, startAt, endAt);
                return;
            }
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
                reservationSlotLockRepository.unlockAll(slotLockHandle.get());
            }
        } finally {
            promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        }
    }

    @Transactional
    public PromotionAcceptResponse accept(Long promotionId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        WaitingQueuePromotion promotion = promotionRepository.findByIdAndUserId(promotionId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));

        validateOfferedAndNotExpired(promotion);

        Long resourceId = promotion.getResource().getId();
        LocalDateTime startAt = promotion.getStartAt();
        LocalDateTime endAt = promotion.getEndAt();

        // Lock 획득 순서: PromotionLock(상위) → ReservationSlotLock(하위) 고정. 프로젝트 전체에서
        // 이 순서를 역전시키지 않는다(tryPromote()도 동일 순서). PromotionLock은 Promotion 상태
        // 전이를, ReservationSlotLock은 Resource 시간 점유권을 각각 보호하는 별개의 책임이다.
        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
        try {
            ReservationSlotLockHandle slotLockHandle = reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
            try {
                WaitingQueuePromotion current = promotionRepository.findById(promotion.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));
                validateOfferedAndNotExpired(current);

                boolean hasConflict = reservationRepository.existsOverlapping(
                        resourceId, ReservationStatus.OCCUPYING_STATUSES, startAt, endAt);
                if (hasConflict) {
                    throw new BusinessException(ErrorCode.RESERVATION_TIME_CONFLICT);
                }

                LocalDateTime now = LocalDateTime.now();
                current.accept(now);

                Reservation reservation = Reservation.builder()
                        .reservationBatchId(UUID.randomUUID())
                        .user(current.getUser())
                        .resource(current.getResource())
                        .startAt(current.getStartAt())
                        .endAt(current.getEndAt())
                        .build();
                Reservation savedReservation = reservationRepository.save(reservation);
                current.linkReservation(savedReservation);

                removeOfferedTtl(current.getId());
                LocalDateTime checkInDeadline = now.plus(CHECK_IN_TTL);
                registerCheckInTtl(savedReservation.getId(), CHECK_IN_TTL);

                return new PromotionAcceptResponse(
                        current.getId(), current.getStatus(), savedReservation.getId(), checkInDeadline);
            } finally {
                reservationSlotLockRepository.unlockAll(slotLockHandle);
            }
        } finally {
            promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        }
    }

    @Transactional
    public void reject(Long promotionId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        WaitingQueuePromotion promotion = promotionRepository.findByIdAndUserId(promotionId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));

        if (promotion.getStatus() != PromotionStatus.OFFERED) {
            throw new BusinessException(ErrorCode.PROMOTION_NOT_OFFERED);
        }

        Long resourceId = promotion.getResource().getId();
        LocalDateTime startAt = promotion.getStartAt();
        LocalDateTime endAt = promotion.getEndAt();

        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
        try {
            WaitingQueuePromotion current = promotionRepository.findById(promotion.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));
            if (current.getStatus() != PromotionStatus.OFFERED) {
                throw new BusinessException(ErrorCode.PROMOTION_NOT_OFFERED);
            }
            current.reject(LocalDateTime.now());
            removeOfferedTtl(current.getId());
        } finally {
            promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        }

        tryPromote(resourceId, startAt, endAt);
    }

    @Transactional
    public void handleOfferedExpiration(Long promotionId) {
        WaitingQueuePromotion promotion = promotionRepository.findById(promotionId).orElse(null);
        if (promotion == null || promotion.getStatus() != PromotionStatus.OFFERED) {
            return;
        }

        Long resourceId = promotion.getResource().getId();
        LocalDateTime startAt = promotion.getStartAt();
        LocalDateTime endAt = promotion.getEndAt();

        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt).orElse(null);
        if (lockToken == null) {
            log.warn("Promotion Lock 획득 실패로 OFFERED 만료 처리를 건너뜁니다. promotionId={}", promotionId);
            return;
        }
        try {
            WaitingQueuePromotion current = promotionRepository.findById(promotionId).orElse(null);
            if (current == null || current.getStatus() != PromotionStatus.OFFERED) {
                return;
            }
            current.expire(LocalDateTime.now());
        } finally {
            promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        }

        tryPromote(resourceId, startAt, endAt);
    }

    @Transactional
    public boolean checkInPromotedReservation(Reservation reservation, LocalDateTime now) {
        WaitingQueuePromotion promotion = promotionRepository.findByReservationId(reservation.getId()).orElse(null);
        if (promotion == null || promotion.getStatus() != PromotionStatus.ACCEPTED) {
            return false;
        }

        Long resourceId = promotion.getResource().getId();
        LocalDateTime startAt = promotion.getStartAt();
        LocalDateTime endAt = promotion.getEndAt();

        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
        try {
            LocalDateTime deadline = promotion.getAcceptedAt().plus(CHECK_IN_TTL);
            // deadline 초과는 서버 오류가 아닌 정상적인 비즈니스 실패이므로 Service에서 먼저
            // BusinessException으로 변환해 API가 500이 아닌 409를 반환하도록 한다.
            // Reservation.checkInByPromotion()의 동일한 시간 검증은 최종 방어선으로 유지한다.
            if (now.isAfter(deadline)) {
                throw new BusinessException(ErrorCode.PROMOTION_CHECKIN_EXPIRED);
            }
            reservation.checkInByPromotion(now, deadline);
            removeCheckInTtl(reservation.getId());
            return true;
        } finally {
            promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        }
    }

    @Transactional
    public void handleCheckInTimeout(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.CONFIRMED) {
            return;
        }

        Long resourceId = reservation.getResource().getId();
        LocalDateTime startAt = reservation.getStartAt();
        LocalDateTime endAt = reservation.getEndAt();

        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt).orElse(null);
        if (lockToken == null) {
            log.warn("Promotion Lock 획득 실패로 체크인 Timeout 처리를 건너뜁니다. reservationId={}", reservationId);
            return;
        }
        try {
            Reservation current = reservationRepository.findById(reservationId).orElse(null);
            if (current == null || current.getStatus() != ReservationStatus.CONFIRMED) {
                return;
            }
            current.cancel(CancelReason.PROMOTION_CHECKIN_TIMEOUT);
        } finally {
            promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        }

        tryPromote(resourceId, startAt, endAt);
    }

    private void validateOfferedAndNotExpired(WaitingQueuePromotion promotion) {
        if (promotion.getStatus() != PromotionStatus.OFFERED) {
            throw new BusinessException(ErrorCode.PROMOTION_NOT_OFFERED);
        }
        if (!LocalDateTime.now().isBefore(promotion.getExpiresAt())) {
            throw new BusinessException(ErrorCode.PROMOTION_EXPIRED);
        }
    }

    private WaitingQueue pollNextWaitingCandidate(Long resourceId, LocalDateTime startAt) {
        List<Long> waitingQueueIds = waitingQueueRedisRepository.findAll(resourceId, startAt);
        for (Long waitingQueueId : waitingQueueIds) {
            Optional<WaitingQueue> found = waitingQueueRepository.findById(waitingQueueId);
            if (found.isPresent() && found.get().getStatus() == WaitingQueueStatus.WAITING) {
                return found.get();
            }
            // 취소 등으로 이미 WAITING이 아닌 stale 항목은 ZSET에서 제거하고 다음 후보를 확인한다
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

    private void removeOfferedTtl(Long promotionId) {
        try {
            promotionOfferedRepository.remove(promotionId);
        } catch (RuntimeException e) {
            log.warn("Promotion OFFERED TTL 삭제에 실패했습니다. promotionId={}", promotionId, e);
        }
    }

    private void registerCheckInTtl(Long reservationId, Duration ttl) {
        try {
            promotionCheckInRepository.register(reservationId, ttl);
        } catch (RuntimeException e) {
            log.warn("Promotion Check-In TTL 등록에 실패했습니다. reservationId={}", reservationId, e);
        }
    }

    private void removeCheckInTtl(Long reservationId) {
        try {
            promotionCheckInRepository.remove(reservationId);
        } catch (RuntimeException e) {
            log.warn("Promotion Check-In TTL 삭제에 실패했습니다. reservationId={}", reservationId, e);
        }
    }

    private void publishPromotedEvent(WaitingQueuePromotion promotion, LocalDateTime promotedAt) {
        WaitingQueuePromotedEvent event = new WaitingQueuePromotedEvent(
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
