package com.gymflow.domain.reservation.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ReservationRepositoryTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("reservationBatchId에는 UNIQUE 제약조건이 없어 동일한 batchId로 여러 Reservation을 저장할 수 있다")
    void save_MultipleReservationsWithSameBatchId_ShouldSucceed() {
        // given
        User user = persistUser();
        Resource resourceA = persistResource("Chest Press A-1");
        Resource resourceB = persistResource("Chest Press A-2");
        UUID batchId = UUID.randomUUID();

        Reservation first = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(resourceA)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        Reservation second = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(resourceB)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        // when
        assertThatCode(() -> {
            reservationRepository.save(first);
            reservationRepository.save(second);
            entityManager.flush();
        }).doesNotThrowAnyException();

        // then
        List<Reservation> saved = reservationRepository.findAll();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(Reservation::getReservationBatchId)
                .containsExactly(batchId, batchId);
    }

    @Test
    @DisplayName("예약을 취소하면 status와 cancelReason이 실제 DB에 저장된다")
    void cancel_ShouldPersistStatusAndCancelReason() {
        // given
        User user = persistUser();
        Resource resource = persistResource("Chest Press A-1");
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 30))
                .build();
        Reservation saved = reservationRepository.save(reservation);
        entityManager.flush();

        // when
        saved.cancel(CancelReason.SCHEDULE_CHANGE);
        entityManager.flush();
        entityManager.clear();

        // then
        Reservation reloaded = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reloaded.getCancelReason()).isEqualTo(CancelReason.SCHEDULE_CHANGE);
    }

    @Test
    @DisplayName("기존 예약과 시간이 겹치면 existsOverlapping은 true를 반환한다")
    void existsOverlapping_WithOverlappingTimeRange_ShouldReturnTrue() {
        // given
        User user = persistUser();
        Resource resource = persistResource("Chest Press A-1");

        Reservation existing = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 30))
                .build();
        reservationRepository.save(existing);
        entityManager.flush();

        // when
        boolean overlaps = reservationRepository.existsOverlapping(
                resource.getId(),
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 8, 12, 14, 15),
                LocalDateTime.of(2026, 8, 12, 14, 45));

        // then
        assertThat(overlaps).isTrue();
    }

    @Test
    @DisplayName("기존 예약의 endAt과 새 예약의 startAt이 같으면 existsOverlapping은 false를 반환한다")
    void existsOverlapping_WithAdjacentTimeRange_ShouldReturnFalse() {
        // given
        User user = persistUser();
        Resource resource = persistResource("Chest Press A-1");

        Reservation existing = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 30))
                .build();
        reservationRepository.save(existing);
        entityManager.flush();

        // when
        boolean overlaps = reservationRepository.existsOverlapping(
                resource.getId(),
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 8, 12, 14, 30),
                LocalDateTime.of(2026, 8, 12, 15, 0));

        // then
        assertThat(overlaps).isFalse();
    }

    @Test
    @DisplayName("findAllByUserId는 현재 사용자의 예약만 조회한다")
    void findAllByUserId_ShouldReturnOnlyThatUsersReservations() {
        // given
        User owner = persistUser("owner@gymflow.com");
        User other = persistUser("other@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");

        Reservation ownerReservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 30))
                .build();
        Reservation otherReservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(other)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 11, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 11, 30))
                .build();
        reservationRepository.save(ownerReservation);
        reservationRepository.save(otherReservation);
        entityManager.flush();

        // when
        Page<Reservation> page = reservationRepository.findAllByUserId(owner.getId(), PageRequest.of(0, 10));

        // then
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getUser().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("findAllByUserId는 Pageable에 따라 페이징된 결과를 반환한다")
    void findAllByUserId_ShouldSupportPaging() {
        // given
        User owner = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");

        for (int i = 0; i < 3; i++) {
            Reservation reservation = Reservation.builder()
                    .reservationBatchId(UUID.randomUUID())
                    .user(owner)
                    .resource(resource)
                    .startAt(LocalDateTime.of(2026, 8, 12, 9 + i, 0))
                    .endAt(LocalDateTime.of(2026, 8, 12, 9 + i, 30))
                    .build();
            reservationRepository.save(reservation);
        }
        entityManager.flush();

        // when
        Page<Reservation> page = reservationRepository.findAllByUserId(owner.getId(), PageRequest.of(0, 2));

        // then
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("findAllByUserId는 상태와 무관하게 사용자의 모든 예약을 조회한다")
    void findAllByUserId_ShouldReturnReservationsOfVariousStatuses() {
        // given
        User owner = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");

        Reservation confirmed = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 9, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 9, 30))
                .build();

        Reservation completed = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 30))
                .build();
        ReflectionTestUtils.setField(completed, "status", ReservationStatus.COMPLETED);

        Reservation cancelled = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 11, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 11, 30))
                .build();
        ReflectionTestUtils.setField(cancelled, "status", ReservationStatus.CANCELLED);

        reservationRepository.save(confirmed);
        reservationRepository.save(completed);
        reservationRepository.save(cancelled);
        entityManager.flush();

        // when
        Page<Reservation> page = reservationRepository.findAllByUserId(owner.getId(), PageRequest.of(0, 10));

        // then
        assertThat(page.getContent()).extracting(Reservation::getStatus)
                .containsExactlyInAnyOrder(
                        ReservationStatus.CONFIRMED, ReservationStatus.COMPLETED, ReservationStatus.CANCELLED);
    }

    @Test
    @DisplayName("findByIdAndUserId는 본인 소유의 예약을 조회한다")
    void findByIdAndUserId_WithOwner_ShouldReturnReservation() {
        // given
        User owner = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 9, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 9, 30))
                .build();
        Reservation saved = reservationRepository.save(reservation);
        entityManager.flush();

        // when
        Optional<Reservation> found = reservationRepository.findByIdAndUserId(saved.getId(), owner.getId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findByIdAndUserId는 다른 사용자의 예약을 조회하면 빈 값을 반환한다")
    void findByIdAndUserId_WithOtherUser_ShouldReturnEmpty() {
        // given
        User owner = persistUser("owner@gymflow.com");
        User other = persistUser("other@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(owner)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 9, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 9, 30))
                .build();
        Reservation saved = reservationRepository.save(reservation);
        entityManager.flush();

        // when
        Optional<Reservation> found = reservationRepository.findByIdAndUserId(saved.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("예약을 연장하면 endAt과 extension_count가 실제 DB에 저장된다")
    void extend_ShouldPersistEndAtAndExtensionCount() {
        // given
        User user = persistUser();
        Resource resource = persistResource("Chest Press A-1");
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
        Reservation saved = reservationRepository.save(reservation);
        entityManager.flush();

        // when
        saved.extend(LocalDateTime.of(2026, 8, 12, 14, 30));
        entityManager.flush();
        entityManager.clear();

        // then
        Reservation reloaded = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEndAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 14, 30));
        assertThat(reloaded.getExtensionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("existsOverlappingExcludingReservation은 자기 자신의 기존 예약을 충돌로 판단하지 않는다")
    void existsOverlappingExcludingReservation_WithOwnReservation_ShouldReturnFalse() {
        // given
        User user = persistUser();
        Resource resource = persistResource("Chest Press A-1");

        Reservation existing = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 30))
                .build();
        Reservation saved = reservationRepository.save(existing);
        entityManager.flush();

        // when
        boolean overlaps = reservationRepository.existsOverlappingExcludingReservation(
                resource.getId(),
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 8, 12, 14, 0),
                LocalDateTime.of(2026, 8, 12, 14, 45),
                saved.getId());

        // then
        assertThat(overlaps).isFalse();
    }

    @Test
    @DisplayName("existsOverlappingExcludingReservation은 다른 예약과 겹치면 true를 반환한다")
    void existsOverlappingExcludingReservation_WithOtherOverlappingReservation_ShouldReturnTrue() {
        // given
        User user = persistUser();
        Resource resource = persistResource("Chest Press A-1");

        Reservation target = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
        Reservation other = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 20))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 40))
                .build();
        Reservation savedTarget = reservationRepository.save(target);
        reservationRepository.save(other);
        entityManager.flush();

        // when
        boolean overlaps = reservationRepository.existsOverlappingExcludingReservation(
                resource.getId(),
                ReservationStatus.CONFIRMED,
                LocalDateTime.of(2026, 8, 12, 14, 15),
                LocalDateTime.of(2026, 8, 12, 14, 30),
                savedTarget.getId());

        // then
        assertThat(overlaps).isTrue();
    }

    @Test
    @DisplayName("findByIdAndUserId는 존재하지 않는 예약이면 빈 값을 반환한다")
    void findByIdAndUserId_WithNonExistentId_ShouldReturnEmpty() {
        // given
        User owner = persistUser("owner@gymflow.com");

        // when
        Optional<Reservation> found = reservationRepository.findByIdAndUserId(999_999L, owner.getId());

        // then
        assertThat(found).isEmpty();
    }

    private User persistUser() {
        return persistUser("batch-test@gymflow.com");
    }

    private User persistUser(String email) {
        User user = User.builder()
                .email(email)
                .password("securePassword123")
                .name("Batch Tester")
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
}
