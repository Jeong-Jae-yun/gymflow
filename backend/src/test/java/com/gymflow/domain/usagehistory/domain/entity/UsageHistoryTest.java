package com.gymflow.domain.usagehistory.domain.entity;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageHistoryTest {

    private User createUser() {
        return User.builder()
                .email("test@gymflow.com")
                .password("securePassword123")
                .name("John Doe")
                .build();
    }

    private Resource createResource() {
        return Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
    }

    private Reservation createReservation(User user, Resource resource) {
        return Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 30))
                .build();
    }

    @Test
    @DisplayName("UsageHistory는 필수 값으로 정상 생성된다")
    void createUsageHistory_WithRequiredFields_ShouldSucceed() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 12, 14, 3);
        LocalDateTime endedAt = LocalDateTime.of(2026, 8, 12, 14, 22);

        // when
        UsageHistory usageHistory = UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build();

        // then
        assertThat(usageHistory.getReservation()).isSameAs(reservation);
        assertThat(usageHistory.getUser()).isSameAs(user);
        assertThat(usageHistory.getResource()).isSameAs(resource);
        assertThat(usageHistory.getStartedAt()).isEqualTo(startedAt);
        assertThat(usageHistory.getEndedAt()).isEqualTo(endedAt);
    }

    @Test
    @DisplayName("reservation이 null이면 생성에 실패한다")
    void createUsageHistory_WithNullReservation_ShouldThrowException() {
        // given
        User user = createUser();
        Resource resource = createResource();

        // when & then
        assertThatThrownBy(() -> UsageHistory.builder()
                .reservation(null)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reservation");
    }

    @Test
    @DisplayName("user가 null이면 생성에 실패한다")
    void createUsageHistory_WithNullUser_ShouldThrowException() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);

        // when & then
        assertThatThrownBy(() -> UsageHistory.builder()
                .reservation(reservation)
                .user(null)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User");
    }

    @Test
    @DisplayName("resource가 null이면 생성에 실패한다")
    void createUsageHistory_WithNullResource_ShouldThrowException() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);

        // when & then
        assertThatThrownBy(() -> UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(null)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource");
    }

    @Test
    @DisplayName("startedAt이 null이면 생성에 실패한다")
    void createUsageHistory_WithNullStartedAt_ShouldThrowException() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);

        // when & then
        assertThatThrownBy(() -> UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(null)
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startedAt");
    }

    @Test
    @DisplayName("endedAt이 null이면 생성에 실패한다")
    void createUsageHistory_WithNullEndedAt_ShouldThrowException() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);

        // when & then
        assertThatThrownBy(() -> UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .endedAt(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endedAt");
    }

    @Test
    @DisplayName("startedAt이 endedAt과 같으면 생성에 실패한다")
    void createUsageHistory_WithStartedAtEqualToEndedAt_ShouldThrowException() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 12, 14, 3);

        // when & then
        assertThatThrownBy(() -> UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(sameTime)
                .endedAt(sameTime)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startedAt");
    }

    @Test
    @DisplayName("startedAt이 endedAt보다 이후이면 생성에 실패한다")
    void createUsageHistory_WithStartedAtAfterEndedAt_ShouldThrowException() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);

        // when & then
        assertThatThrownBy(() -> UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(LocalDateTime.of(2026, 8, 12, 14, 22))
                .endedAt(LocalDateTime.of(2026, 8, 12, 14, 3))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startedAt");
    }

    @Test
    @DisplayName("duration은 startedAt과 endedAt의 분 단위 차이로 계산된다")
    void duration_ShouldBeCalculatedFromActualTimeDifference() {
        // given
        User user = createUser();
        Resource resource = createResource();
        Reservation reservation = createReservation(user, resource);
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 12, 14, 3);
        LocalDateTime endedAt = LocalDateTime.of(2026, 8, 12, 14, 22);

        // when
        UsageHistory usageHistory = UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build();

        // then
        assertThat(usageHistory.getDuration()).isEqualTo(19);
    }
}
