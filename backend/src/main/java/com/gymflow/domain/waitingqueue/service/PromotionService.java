package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.redis.ResourceAvailabilityLockRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionCheckInRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionLockRepository;
import com.gymflow.domain.waitingqueue.domain.redis.PromotionOfferedRepository;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueuePromotionRepository;
import com.gymflow.domain.waitingqueue.dto.response.PromotionAcceptResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.common.transaction.TransactionAwareLockReleaser;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final Duration CHECK_IN_TTL = Duration.ofMinutes(1);

    private final WaitingQueuePromotionRepository promotionRepository;
    private final ReservationRepository reservationRepository;
    private final ResourceAvailabilityLockRepository resourceAvailabilityLockRepository;
    private final PromotionLockRepository promotionLockRepository;
    private final ReservationSlotLockRepository reservationSlotLockRepository;
    private final PromotionOfferedRepository promotionOfferedRepository;
    private final PromotionCheckInRepository promotionCheckInRepository;
    private final TransactionAwareLockReleaser lockReleaser;
    private final PromotionProcessor promotionProcessor;
    private final PromotionMetrics promotionMetrics;

    @Autowired
    @Lazy
    private PromotionService self;
    private record PromotionSlot(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
    }

    @Transactional(readOnly = true)
    public boolean hasActiveOffer(Long resourceId, LocalDateTime startAt, LocalDateTime endAt) {
        return promotionRepository.existsOverlapping(resourceId, PromotionStatus.OFFERED, startAt, endAt);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveOfferedPromotion(Long resourceId, LocalDateTime now) {
        return promotionRepository.existsByResourceIdAndStatusAndExpiresAtAfter(resourceId, PromotionStatus.OFFERED, now);
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


        String availabilityLockToken = resourceAvailabilityLockRepository.tryLock(resourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));

        boolean releaseAvailabilityImmediately = true;
        try {
            String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
            boolean releasePromotionImmediately = true;
            try {
                ReservationSlotLockHandle slotLockHandle = reservationSlotLockRepository.tryLockAll(resourceId, startAt, endAt)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
                releasePromotionImmediately = false;
                releaseAvailabilityImmediately = false;
                Runnable unlockAction = () -> {
                    reservationSlotLockRepository.unlockAll(slotLockHandle);
                    promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
                    resourceAvailabilityLockRepository.unlock(resourceId, availabilityLockToken);
                };
                boolean deferred = lockReleaser.register(unlockAction);
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

                    promotionMetrics.recordAccepted();
                    return new PromotionAcceptResponse(
                            current.getId(), current.getStatus(), savedReservation.getId(), checkInDeadline);
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

    public void reject(Long promotionId) {
        PromotionSlot slot = self.rejectTransactional(promotionId);
        promotionProcessor.tryPromote(slot.resourceId(), slot.startAt(), slot.endAt());
    }

    @Transactional
    public PromotionSlot rejectTransactional(Long promotionId) {
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
        Runnable unlockAction = () -> promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        boolean deferred = lockReleaser.register(unlockAction);
        try {
            WaitingQueuePromotion current = promotionRepository.findById(promotion.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PROMOTION_NOT_FOUND));
            if (current.getStatus() != PromotionStatus.OFFERED) {
                throw new BusinessException(ErrorCode.PROMOTION_NOT_OFFERED);
            }
            current.reject(LocalDateTime.now());
            removeOfferedTtl(current.getId());
            promotionMetrics.recordRejected();
        } finally {
            if (!deferred) {
                unlockAction.run();
            }
        }

        return new PromotionSlot(resourceId, startAt, endAt);
    }

    public void handleOfferedExpiration(Long promotionId) {
        PromotionSlot slot = self.handleOfferedExpirationTransactional(promotionId);
        if (slot != null) {
            promotionProcessor.tryPromote(slot.resourceId(), slot.startAt(), slot.endAt());
        }
    }

    @Transactional
    public PromotionSlot handleOfferedExpirationTransactional(Long promotionId) {
        WaitingQueuePromotion promotion = promotionRepository.findById(promotionId).orElse(null);
        if (promotion == null || promotion.getStatus() != PromotionStatus.OFFERED) {
            return null;
        }

        Long resourceId = promotion.getResource().getId();
        LocalDateTime startAt = promotion.getStartAt();
        LocalDateTime endAt = promotion.getEndAt();

        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt).orElse(null);
        if (lockToken == null) {
            log.warn("Promotion Lock 획득 실패로 OFFERED 만료 처리를 건너뜁니다. promotionId={}", promotionId);
            return null;
        }
        Runnable unlockAction = () -> promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        boolean deferred = lockReleaser.register(unlockAction);
        try {
            WaitingQueuePromotion current = promotionRepository.findById(promotionId).orElse(null);
            if (current == null || current.getStatus() != PromotionStatus.OFFERED) {
                return null;
            }
            current.expire(LocalDateTime.now());
            promotionMetrics.recordExpired();
        } finally {
            if (!deferred) {
                unlockAction.run();
            }
        }

        return new PromotionSlot(resourceId, startAt, endAt);
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
        Runnable unlockAction = () -> promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        boolean deferred = lockReleaser.register(unlockAction);
        try {
            LocalDateTime deadline = promotion.getAcceptedAt().plus(CHECK_IN_TTL);
            if (now.isAfter(deadline)) {
                throw new BusinessException(ErrorCode.PROMOTION_CHECKIN_EXPIRED);
            }
            reservation.checkInByPromotion(now, deadline);
            removeCheckInTtl(reservation.getId());
            return true;
        } finally {
            if (!deferred) {
                unlockAction.run();
            }
        }
    }

    public void handleCheckInTimeout(Long reservationId) {
        PromotionSlot slot = self.handleCheckInTimeoutTransactional(reservationId);
        if (slot != null) {
            promotionProcessor.tryPromote(slot.resourceId(), slot.startAt(), slot.endAt());
        }
    }

    @Transactional
    public PromotionSlot handleCheckInTimeoutTransactional(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.CONFIRMED) {
            return null;
        }

        Long resourceId = reservation.getResource().getId();
        LocalDateTime startAt = reservation.getStartAt();
        LocalDateTime endAt = reservation.getEndAt();

        String lockToken = promotionLockRepository.tryLock(resourceId, startAt, endAt).orElse(null);
        if (lockToken == null) {
            log.warn("Promotion Lock 획득 실패로 체크인 Timeout 처리를 건너뜁니다. reservationId={}", reservationId);
            return null;
        }
        Runnable unlockAction = () -> promotionLockRepository.unlock(resourceId, startAt, endAt, lockToken);
        boolean deferred = lockReleaser.register(unlockAction);
        try {
            Reservation current = reservationRepository.findById(reservationId).orElse(null);
            if (current == null || current.getStatus() != ReservationStatus.CONFIRMED) {
                return null;
            }
            current.cancel(CancelReason.PROMOTION_CHECKIN_TIMEOUT);
        } finally {
            if (!deferred) {
                unlockAction.run();
            }
        }

        return new PromotionSlot(resourceId, startAt, endAt);
    }

    private void validateOfferedAndNotExpired(WaitingQueuePromotion promotion) {
        if (promotion.getStatus() != PromotionStatus.OFFERED) {
            throw new BusinessException(ErrorCode.PROMOTION_NOT_OFFERED);
        }
        if (!LocalDateTime.now().isBefore(promotion.getExpiresAt())) {
            throw new BusinessException(ErrorCode.PROMOTION_EXPIRED);
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
}
