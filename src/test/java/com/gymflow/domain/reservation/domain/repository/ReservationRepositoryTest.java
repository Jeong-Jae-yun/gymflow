package com.gymflow.domain.reservation.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
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

import java.time.LocalDateTime;
import java.util.List;
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

    private User persistUser() {
        User user = User.builder()
                .email("batch-test@gymflow.com")
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
