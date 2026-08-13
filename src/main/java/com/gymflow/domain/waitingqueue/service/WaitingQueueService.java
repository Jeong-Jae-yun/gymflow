package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;
import com.gymflow.domain.waitingqueue.mapper.WaitingQueueMapper;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class  WaitingQueueService {

    private final WaitingQueueRepository waitingQueueRepository;
    private final ResourceRepository resourceRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;

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

        boolean alreadyWaiting = waitingQueueRepository.existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
                currentUserId, resource.getId(), startAt, endAt, WaitingQueueStatus.WAITING);
        if (alreadyWaiting) {
            throw new BusinessException(ErrorCode.WAITING_QUEUE_ALREADY_EXISTS);
        }

        boolean reservationAvailable = !reservationRepository.existsOverlapping(
                resource.getId(), ReservationStatus.CONFIRMED, startAt, endAt);
        if (reservationAvailable) {
            throw new BusinessException(ErrorCode.WAITING_QUEUE_NOT_AVAILABLE);
        }

        User user = userRepository.getReferenceById(currentUserId);

        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();

        WaitingQueue savedWaitingQueue = waitingQueueRepository.save(waitingQueue);

        return WaitingQueueMapper.toResponse(savedWaitingQueue);
    }

    public Page<WaitingQueueResponse> getMyWaitingQueues(Pageable pageable) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        return waitingQueueRepository.findAllByUserId(currentUserId, pageable)
                .map(WaitingQueueMapper::toResponse);
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
    }
}
