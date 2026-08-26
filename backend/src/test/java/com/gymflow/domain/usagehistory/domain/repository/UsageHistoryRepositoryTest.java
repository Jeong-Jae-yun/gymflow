package com.gymflow.domain.usagehistory.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.projection.ResourceUsageStatisticsProjection;
import com.gymflow.domain.usagehistory.domain.repository.projection.UsageSummaryProjection;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
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
                .email("usage-history-test-" + UUID.randomUUID() + "@gymflow.com")
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

    private Resource persistResource(ResourceStatus status) {
        Resource resource = Resource.builder()
                .name("Locker A-1")
                .type(ResourceType.LOCKER)
                .capacity(1)
                .build();
        resource.changeStatus(status);
        entityManager.persist(resource);
        return resource;
    }

    private UsageHistory persistUsageHistory(User user, Resource resource, LocalDateTime startedAt, LocalDateTime endedAt) {
        Reservation reservation = persistReservation(user, resource);
        UsageHistory usageHistory = UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build();
        return usageHistoryRepository.save(usageHistory);
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

    @Test
    @DisplayName("findMyUsageHistories는 기간 [from,to)에 해당하는 본인 이력만 startedAt DESC로 반환한다")
    void findMyUsageHistories_WithPeriod_ShouldReturnOnlyMatchingRowsOrderedByStartedAtDesc() {
        // given
        User user = persistUser();
        User otherUser = persistUser();
        Resource resource = persistResource(ResourceStatus.ACTIVE);
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 7, 31, 23, 0), LocalDateTime.of(2026, 7, 31, 23, 30));
        UsageHistory inRangeEarly = persistUsageHistory(user, resource,
                LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 30));
        UsageHistory inRangeLate = persistUsageHistory(user, resource,
                LocalDateTime.of(2026, 8, 15, 10, 0), LocalDateTime.of(2026, 8, 15, 10, 30));
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 30));
        persistUsageHistory(otherUser, resource, LocalDateTime.of(2026, 8, 10, 0, 0), LocalDateTime.of(2026, 8, 10, 0, 30));
        entityManager.flush();
        entityManager.clear();

        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 1, 0, 0);

        // when
        Page<UsageHistory> page = usageHistoryRepository.findMyUsageHistories(
                user.getId(), from, to, PageRequest.of(0, 20));

        // then
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(UsageHistory::getId)
                .containsExactly(inRangeLate.getId(), inRangeEarly.getId());
    }

    @Test
    @DisplayName("findMyUsageHistories는 from만 주어지면 그 이후 전체 이력을 반환한다")
    void findMyUsageHistories_WithFromOnly_ShouldReturnAllAfterFrom() {
        // given
        User user = persistUser();
        Resource resource = persistResource(ResourceStatus.ACTIVE);
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 1, 0, 30));
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 30));
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 30));
        entityManager.flush();
        entityManager.clear();

        // when
        Page<UsageHistory> page = usageHistoryRepository.findMyUsageHistories(
                user.getId(), LocalDateTime.of(2026, 8, 1, 0, 0), null, PageRequest.of(0, 20));

        // then
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("findMyUsageHistories는 to만 주어지면 그 이전 전체 이력을 반환하며 to는 제외한다")
    void findMyUsageHistories_WithToOnly_ShouldReturnAllBeforeToExclusive() {
        // given
        User user = persistUser();
        Resource resource = persistResource(ResourceStatus.ACTIVE);
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 7, 1, 0, 0), LocalDateTime.of(2026, 7, 1, 0, 30));
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 30));
        entityManager.flush();
        entityManager.clear();

        // when
        Page<UsageHistory> page = usageHistoryRepository.findMyUsageHistories(
                user.getId(), null, LocalDateTime.of(2026, 8, 1, 0, 0), PageRequest.of(0, 20));

        // then
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("findMyUsageHistories는 INACTIVE Resource의 과거 이력도 포함한다")
    void findMyUsageHistories_ShouldIncludeInactiveResourceHistory() {
        // given
        User user = persistUser();
        Resource inactiveResource = persistResource(ResourceStatus.INACTIVE);
        persistUsageHistory(user, inactiveResource,
                LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 30));
        entityManager.flush();
        entityManager.clear();

        // when
        Page<UsageHistory> page = usageHistoryRepository.findMyUsageHistories(
                user.getId(), null, null, PageRequest.of(0, 20));

        // then
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getResource().getStatus()).isEqualTo(ResourceStatus.INACTIVE);
    }

    @Test
    @DisplayName("findUsageSummaryByUserId는 기간 내 총 이용 횟수와 총 이용 시간을 COUNT/SUM으로 계산한다")
    void findUsageSummaryByUserId_ShouldReturnCountAndSum() {
        // given
        User user = persistUser();
        Resource resource = persistResource(ResourceStatus.ACTIVE);
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 20));
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 8, 2, 0, 0), LocalDateTime.of(2026, 8, 2, 0, 40));
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 10));
        entityManager.flush();
        entityManager.clear();

        // when
        UsageSummaryProjection summary = usageHistoryRepository.findUsageSummaryByUserId(
                user.getId(), LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0));

        // then
        assertThat(summary.getTotalUsageCount()).isEqualTo(2);
        assertThat(summary.getTotalUsageMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("findUsageSummaryByUserId는 이력이 없으면 SUM 대신 0을 반환한다")
    void findUsageSummaryByUserId_WithNoHistory_ShouldReturnZero() {
        // given
        User user = persistUser();
        entityManager.flush();

        // when
        UsageSummaryProjection summary = usageHistoryRepository.findUsageSummaryByUserId(user.getId(), null, null);

        // then
        assertThat(summary.getTotalUsageCount()).isZero();
        assertThat(summary.getTotalUsageMinutes()).isZero();
    }

    @Test
    @DisplayName("findResourceUsageStatisticsByUserId는 Resource별 이용 횟수/시간을 usageCount 내림차순으로 반환하며 INACTIVE Resource도 포함한다")
    void findResourceUsageStatisticsByUserId_ShouldGroupByResourceOrderedByUsageCountDesc() {
        // given
        User user = persistUser();
        Resource popularResource = persistResource(ResourceStatus.ACTIVE);
        Resource inactiveResource = persistResource(ResourceStatus.INACTIVE);
        persistUsageHistory(user, popularResource, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 20));
        persistUsageHistory(user, popularResource, LocalDateTime.of(2026, 8, 2, 0, 0), LocalDateTime.of(2026, 8, 2, 0, 20));
        persistUsageHistory(user, inactiveResource, LocalDateTime.of(2026, 8, 3, 0, 0), LocalDateTime.of(2026, 8, 3, 0, 10));
        entityManager.flush();
        entityManager.clear();

        // when
        List<ResourceUsageStatisticsProjection> stats =
                usageHistoryRepository.findResourceUsageStatisticsByUserId(user.getId(), null, null);

        // then
        assertThat(stats).hasSize(2);
        assertThat(stats.get(0).getResourceId()).isEqualTo(popularResource.getId());
        assertThat(stats.get(0).getUsageCount()).isEqualTo(2);
        assertThat(stats.get(0).getTotalUsageMinutes()).isEqualTo(40);
        assertThat(stats.get(1).getResourceId()).isEqualTo(inactiveResource.getId());
        assertThat(stats.get(1).getResourceStatus()).isEqualTo(ResourceStatus.INACTIVE);
    }

    @Test
    @DisplayName("findUsageSummaryByResourceId는 특정 Resource의 기간 내 총 이용 횟수/시간을 계산한다")
    void findUsageSummaryByResourceId_ShouldReturnCountAndSum() {
        // given
        User user = persistUser();
        Resource resource = persistResource(ResourceStatus.ACTIVE);
        Resource otherResource = persistResource(ResourceStatus.ACTIVE);
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 15));
        persistUsageHistory(user, resource, LocalDateTime.of(2026, 8, 2, 0, 0), LocalDateTime.of(2026, 8, 2, 0, 15));
        persistUsageHistory(user, otherResource, LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 1, 0));
        entityManager.flush();
        entityManager.clear();

        // when
        UsageSummaryProjection summary = usageHistoryRepository.findUsageSummaryByResourceId(resource.getId(), null, null);

        // then
        assertThat(summary.getTotalUsageCount()).isEqualTo(2);
        assertThat(summary.getTotalUsageMinutes()).isEqualTo(30);
    }
}
