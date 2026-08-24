package com.gymflow.domain.reservation.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationNoShowRepository;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockHandle;
import com.gymflow.domain.reservation.domain.redis.ReservationSlotLockRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.mapper.ReservationMapper;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.UsageHistoryRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.service.PromotionService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;
    private final UsageHistoryRepository usageHistoryRepository;
    private final ReservationSlotLockRepository reservationSlotLockRepository;
    private final ReservationNoShowRepository reservationNoShowRepository;
    private final ResourceRankingRedisRepository resourceRankingRedisRepository;
    private final PromotionService promotionService;

    @Transactional
    public ReservationResponse createReservation(ReservationCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Resource resource = resourceRepository.findWithReservationPolicyById(request.resourceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (resource.getStatus() != ResourceStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_ACTIVE);
        }

        ReservationPolicy policy = resource.getReservationPolicy();
        if (policy == null) {
            throw new BusinessException(ErrorCode.RESERVATION_POLICY_NOT_FOUND);
        }

        Integer duration = request.duration();
        if (duration < policy.getMinDuration() || duration > policy.getMaxDuration()) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_DURATION);
        }

        LocalDateTime startAt = request.startAt();
        LocalDateTime endAt = startAt.plusMinutes(duration);


        ReservationSlotLockHandle lockHandle = reservationSlotLockRepository.tryLockAll(resource.getId(), startAt, endAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
        try {
            boolean hasConflict = reservationRepository.existsOverlapping(
                    resource.getId(), ReservationStatus.OCCUPYING_STATUSES, startAt, endAt);
            if (hasConflict) {
                throw new BusinessException(ErrorCode.RESERVATION_TIME_CONFLICT);
            }
            if (promotionService.hasActiveOffer(resource.getId(), startAt, endAt)) {
                throw new BusinessException(ErrorCode.RESERVATION_PROMOTION_RESERVED);
            }

            User user = userRepository.getReferenceById(currentUserId);

            Reservation reservation = Reservation.builder()
                    .reservationBatchId(UUID.randomUUID())
                    .user(user)
                    .resource(resource)
                    .startAt(startAt)
                    .endAt(endAt)
                    .build();

            Reservation savedReservation = reservationRepository.save(reservation);

            registerNoShowKey(savedReservation.getId(), startAt);
            incrementResourceRanking(resource.getId());

            return ReservationMapper.toResponse(savedReservation);
        } finally {
            reservationSlotLockRepository.unlockAll(lockHandle);
        }
    }

    public Page<ReservationResponse> getMyReservations(Pageable pageable) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        return reservationRepository.findAllByUserId(currentUserId, pageable)
                .map(ReservationMapper::toResponse);
    }

    public ReservationResponse getMyReservationDetail(Long reservationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public ReservationResponse cancelReservation(Long reservationId, CancelReservationRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }

        reservation.cancel(request.cancelReason());
        removeNoShowKey(reservation.getId());
        tryPromoteWaitingQueue(reservation);

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public ReservationResponse extendReservation(Long reservationId, ReservationExtensionRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_EXTENDABLE);
        }

        Resource resource = resourceRepository.findWithReservationPolicyById(reservation.getResource().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (resource.getStatus() != ResourceStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_ACTIVE);
        }

        ReservationPolicy policy = resource.getReservationPolicy();
        if (policy == null) {
            throw new BusinessException(ErrorCode.RESERVATION_POLICY_NOT_FOUND);
        }

        if (reservation.getExtensionCount() >= Reservation.MAX_EXTENSION_COUNT) {
            throw new BusinessException(ErrorCode.RESERVATION_EXTENSION_LIMIT_EXCEEDED);
        }

        LocalDateTime newEndAt = reservation.getEndAt().plusMinutes(request.duration());

        long totalMinutes = Duration.between(reservation.getStartAt(), newEndAt).toMinutes();
        if (totalMinutes > policy.getMaxDuration()) {
            throw new BusinessException(ErrorCode.RESERVATION_MAX_DURATION_EXCEEDED);
        }

        LocalDateTime deltaStart = reservation.getEndAt();
        ReservationSlotLockHandle lockHandle = reservationSlotLockRepository.tryLockAll(resource.getId(), deltaStart, newEndAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
        try {
            boolean hasConflict = reservationRepository.existsOverlappingExcludingReservation(
                    resource.getId(), ReservationStatus.OCCUPYING_STATUSES, deltaStart, newEndAt, reservation.getId());
            if (hasConflict) {
                throw new BusinessException(ErrorCode.RESERVATION_TIME_CONFLICT);
            }

            reservation.extend(newEndAt);
            reservationRepository.save(reservation);

            return ReservationMapper.toResponse(reservation);
        } finally {
            reservationSlotLockRepository.unlockAll(lockHandle);
        }
    }

    @Transactional
    public ReservationResponse checkInReservation(Long reservationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CHECKINABLE);
        }

        LocalDateTime now = LocalDateTime.now();
        boolean checkedInByPromotion = promotionService.checkInPromotedReservation(reservation, now);
        if (!checkedInByPromotion) {
            reservation.checkIn(now);
            removeNoShowKey(reservation.getId());
        }

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public ReservationResponse checkOutReservation(Long reservationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CHECKOUTABLE);
        }

        reservation.checkOut();

        UsageHistory usageHistory = UsageHistory.builder()
                .reservation(reservation)
                .user(reservation.getUser())
                .resource(reservation.getResource())
                .startedAt(reservation.getCheckInAt())
                .endedAt(reservation.getCheckOutAt())
                .build();
        usageHistoryRepository.save(usageHistory);

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public ReservationResponse noShow(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        reservation.noShow(LocalDateTime.now());
        tryPromoteWaitingQueue(reservation);

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public void handleNoShowExpiration(Long reservationId) {
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
                reservation.noShow(LocalDateTime.now());
                tryPromoteWaitingQueue(reservation);
            }
        });
    }

    private void tryPromoteWaitingQueue(Reservation reservation) {
        try {
            promotionService.tryPromote(
                    reservation.getResource().getId(), reservation.getStartAt(), reservation.getEndAt());
        } catch (RuntimeException e) {
            log.error("WaitingQueue 자동 승급 처리 중 오류가 발생했습니다. reservationId={}", reservation.getId(), e);
        }
    }

    private void registerNoShowKey(Long reservationId, LocalDateTime startAt) {
        Duration ttl = Duration.between(LocalDateTime.now(), startAt);
        if (ttl.isZero() || ttl.isNegative()) {
            log.warn("NO_SHOW 가능 시점이 이미 지나 Redis Key를 등록하지 않습니다. reservationId={}", reservationId);
            return;
        }
        try {
            reservationNoShowRepository.register(reservationId, ttl);
        } catch (RuntimeException e) {
            log.error("Redis NO_SHOW Key 등록에 실패했습니다. reservationId={}", reservationId, e);
        }
    }

    private void removeNoShowKey(Long reservationId) {
        try {
            reservationNoShowRepository.remove(reservationId);
        } catch (RuntimeException e) {
            log.error("Redis NO_SHOW Key 삭제에 실패했습니다. reservationId={}", reservationId, e);
        }
    }

    private void incrementResourceRanking(Long resourceId) {
        try {
            resourceRankingRedisRepository.incrementReservationCount(resourceId);
        } catch (RuntimeException e) {
            log.warn("Redis Resource Ranking 증가에 실패했습니다. resourceId={}", resourceId, e);
        }
    }
}
