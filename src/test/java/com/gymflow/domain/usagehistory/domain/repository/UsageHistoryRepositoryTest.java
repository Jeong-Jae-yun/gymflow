package com.gymflow.domain.usagehistory.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class UsageHistoryRepositoryTest {

    @Autowired
    private UsageHistoryRepository usageHistoryRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User persistUser() {
        User user = User.builder()
                .email("usage-history-test@gymflow.com")
                .password("securePassword123")
                .name("Usage Tester")
                .build();
        entityManager.persist(user);
        return user;
    }

    private Resource persistResource() {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        entityManager.persist(resource);
        return resource;
    }

    private Reservation persistReservation(User user, Resource resource) {
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 30))
                .build();
        entityManager.persist(reservation);
        return reservation;
    }

    @Test
    @DisplayName("UsageHistory는 Reservation/User/Resource와 연결되어 실제 DB에 저장된다")
    void save_ShouldPersistUsageHistoryWithAssociations() {
        // given
        User user = persistUser();
        Resource resource = persistResource();
        Reservation reservation = persistReservation(user, resource);
        entityManager.flush();

        UsageHistory usageHistory = UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .build();

        // when
        UsageHistory saved = usageHistoryRepository.save(usageHistory);
        entityManager.flush();
        entityManager.clear();

        // then
        UsageHistory reloaded = usageHistoryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getReservation().getId()).isEqualTo(reservation.getId());
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getResource().getId()).isEqualTo(resource.getId());
        assertThat(reloaded.getDuration()).isEqualTo(19);
    }

    @Test
    @DisplayName("하나의 Reservation에는 하나의 UsageHistory만 저장할 수 있다")
    void save_WithDuplicateReservation_ShouldViolateUniqueConstraint() {
        // given
        User user = persistUser();
        Resource resource = persistResource();
        Reservation reservation = persistReservation(user, resource);
        entityManager.flush();

        UsageHistory first = UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .build();
        usageHistoryRepository.save(first);
        entityManager.flush();

        UsageHistory duplicate = UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 15, 0))
                .endedAt(LocalDateTime.of(2026, 8, 12, 15, 10))
                .build();

        // when & then
        assertThatThrownBy(() -> {
            usageHistoryRepository.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("서로 다른 Reservation에 대한 UsageHistory는 각각 정상 저장된다")
    void save_WithDifferentReservations_ShouldSucceed() {
        // given
        User user = persistUser();
        Resource resource = persistResource();
        Reservation firstReservation = persistReservation(user, resource);
        Reservation secondReservation = persistReservation(user, resource);
        entityManager.flush();

        UsageHistory first = UsageHistory.builder()
                .reservation(firstReservation)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .build();
        UsageHistory second = UsageHistory.builder()
                .reservation(secondReservation)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 15, 3))
                .endedAt(LocalDateTime.of(2026, 8, 12, 15, 22))
                .build();

        // when & then
        assertThatCode(() -> {
            usageHistoryRepository.save(first);
            usageHistoryRepository.save(second);
            entityManager.flush();
        }).doesNotThrowAnyException();
        assertThat(usageHistoryRepository.findAll()).hasSize(2);
    }
}
