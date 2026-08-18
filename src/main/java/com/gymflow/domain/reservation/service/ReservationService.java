package com.gymflow.domain.reservation.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationLockRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.mapper.ReservationMapper;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.UsageHistoryRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;
    private final UsageHistoryRepository usageHistoryRepository;
    private final ReservationLockRepository reservationLockRepository;

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


        String lockToken = reservationLockRepository.tryLock(resource.getId(), startAt, endAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_IN_PROGRESS));
        try {
            boolean hasConflict = reservationRepository.existsOverlapping(
                    resource.getId(), ReservationStatus.CONFIRMED, startAt, endAt);
            if (hasConflict) {
                throw new BusinessException(ErrorCode.RESERVATION_TIME_CONFLICT);
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

            return ReservationMapper.toResponse(savedReservation);
        } finally {
            reservationLockRepository.unlock(resource.getId(), startAt, endAt, lockToken);
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

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public ReservationResponse extendReservation(Long reservationId, ReservationExtensionRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.CHECKED_IN) {
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

        boolean hasConflict = reservationRepository.existsOverlappingExcludingReservation(
                resource.getId(), ReservationStatus.CONFIRMED, reservation.getEndAt(), newEndAt, reservation.getId());
        if (hasConflict) {
            throw new BusinessException(ErrorCode.RESERVATION_TIME_CONFLICT);
        }

        reservation.extend(newEndAt);

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public ReservationResponse checkInReservation(Long reservationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        Reservation reservation = reservationRepository.findByIdAndUserId(reservationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CHECKINABLE);
        }

        reservation.checkIn();

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

        return ReservationMapper.toResponse(reservation);
    }

    @Transactional
    public ReservationResponse expire(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));

        reservation.expire(LocalDateTime.now());

        return ReservationMapper.toResponse(reservation);
    }
}
