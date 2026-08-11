package com.gymflow.domain.reservation.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.mapper.ReservationMapper;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
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

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;

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
}
