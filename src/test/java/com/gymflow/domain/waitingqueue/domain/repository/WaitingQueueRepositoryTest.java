package com.gymflow.domain.waitingqueue.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class WaitingQueueRepositoryTest {

    @Autowired
    private WaitingQueueRepository waitingQueueRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser(String email) {
        User user = User.builder()
                .email(email)
                .password("securePassword123")
                .name("Waiting Queue Tester")
                .build();
        entityManager.persist(user);
        return user;
    }

    private Resource persistResource(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        entityManager.persist(resource);
        return resource;
    }

    @Test
    @DisplayName("WaitingQueue는 User/Resource와 연결되어 실제 DB에 저장된다")
    void save_ShouldPersistWaitingQueueWithAssociations() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();

        // when
        WaitingQueue saved = waitingQueueRepository.save(waitingQueue);
        entityManager.flush();
        entityManager.clear();

        // then
        WaitingQueue reloaded = waitingQueueRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getResource().getId()).isEqualTo(resource.getId());
        assertThat(reloaded.getStatus()).isEqualTo(WaitingQueueStatus.WAITING);
    }

    @Test
    @DisplayName("findAllByUserId는 현재 사용자의 대기열만 조회한다")
    void findAllByUserId_ShouldReturnOnlyThatUsersWaitingQueues() {
        // given
        User owner = persistUser("owner@gymflow.com");
        User other = persistUser("other@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");

        WaitingQueue ownerQueue = WaitingQueue.builder()
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
        WaitingQueue otherQueue = WaitingQueue.builder()
                .user(other)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 15, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 15, 15))
                .build();
        waitingQueueRepository.save(ownerQueue);
        waitingQueueRepository.save(otherQueue);
        entityManager.flush();

        // when
        Page<WaitingQueue> page = waitingQueueRepository.findAllByUserId(owner.getId(), PageRequest.of(0, 10));

        // then
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getUser().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("findByIdAndUserId는 다른 사용자의 대기열을 조회하면 빈 값을 반환한다")
    void findByIdAndUserId_WithOtherUser_ShouldReturnEmpty() {
        // given
        User owner = persistUser("owner@gymflow.com");
        User other = persistUser("other@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
        WaitingQueue saved = waitingQueueRepository.save(waitingQueue);
        entityManager.flush();

        // when
        Optional<WaitingQueue> found = waitingQueueRepository.findByIdAndUserId(saved.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus는 동일 시간대의 WAITING 항목이 있으면 true를 반환한다")
    void existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus_WithExistingWaitingEntry_ShouldReturnTrue() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 14, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 12, 14, 15);
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();
        waitingQueueRepository.save(waitingQueue);
        entityManager.flush();

        // when
        boolean exists = waitingQueueRepository.existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
                user.getId(), resource.getId(), startAt, endAt, WaitingQueueStatus.WAITING);

        // then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("CANCELLED 상태인 대기열은 existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(WAITING) 대상이 아니다")
    void existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus_WithCancelledEntry_ShouldReturnFalse() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 14, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 12, 14, 15);
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();
        waitingQueue.cancel();
        waitingQueueRepository.save(waitingQueue);
        entityManager.flush();

        // when
        boolean exists = waitingQueueRepository.existsByUserIdAndResourceIdAndStartAtAndEndAtAndStatus(
                user.getId(), resource.getId(), startAt, endAt, WaitingQueueStatus.WAITING);

        // then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("취소 후 동일한 사용자/Resource/시간대로 다시 저장할 수 있다")
    void save_AfterCancellingPreviousEntry_ShouldSucceed() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 14, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 12, 14, 15);
        WaitingQueue first = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();
        first.cancel();
        waitingQueueRepository.save(first);
        entityManager.flush();

        WaitingQueue second = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();

        // when & then
        assertThatCode(() -> {
            waitingQueueRepository.save(second);
            entityManager.flush();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("대기열을 취소하면 status가 실제 DB에 저장된다")
    void cancel_ShouldPersistStatus() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
        WaitingQueue saved = waitingQueueRepository.save(waitingQueue);
        entityManager.flush();

        // when
        saved.cancel();
        entityManager.flush();
        entityManager.clear();

        // then
        WaitingQueue reloaded = waitingQueueRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WaitingQueueStatus.CANCELLED);
    }

    @Test
    @DisplayName("CONFIRMED 예약과 시간이 겹치면 Reservation.existsOverlapping은 true를 반환하여 대기열 등록 대상이 된다")
    void existsOverlapping_WithConflictingConfirmedReservation_ShouldReturnTrue() {
        // given
        User owner = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
        reservationRepository.save(reservation);
        entityManager.flush();

        // when
        boolean overlaps = reservationRepository.existsOverlapping(
                resource.getId(),
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 8, 12, 14, 0),
                LocalDateTime.of(2026, 8, 12, 14, 15));

        // then
        assertThat(overlaps).isTrue();
    }

    @Test
    @DisplayName("경계가 맞닿는 시간대는 Reservation.existsOverlapping이 false를 반환하여 대기열 등록 대상이 아니다")
    void existsOverlapping_WithAdjacentReservation_ShouldReturnFalse() {
        // given
        User owner = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
        reservationRepository.save(reservation);
        entityManager.flush();

        // when
        boolean overlaps = reservationRepository.existsOverlapping(
                resource.getId(),
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 8, 12, 14, 15),
                LocalDateTime.of(2026, 8, 12, 14, 30));

        // then
        assertThat(overlaps).isFalse();
    }
}
