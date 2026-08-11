package com.gymflow.domain.reservation.domain.entity;

import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

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

    @Test
    @DisplayName("Reservation은 필수 값으로 정상 생성된다")
    void createReservation_WithRequiredFields_ShouldSucceed() {
        // given
        UUID batchId = UUID.randomUUID();
        User user = createUser();
        Resource resource = createResource();
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 12, 10, 15);

        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();

        // then
        assertThat(reservation.getReservationBatchId()).isEqualTo(batchId);
        assertThat(reservation.getUser()).isSameAs(user);
        assertThat(reservation.getResource()).isSameAs(resource);
        assertThat(reservation.getStartAt()).isEqualTo(startAt);
        assertThat(reservation.getEndAt()).isEqualTo(endAt);
    }

    @Test
    @DisplayName("Reservation 생성 시 status는 항상 CONFIRMED이다")
    void createReservation_ShouldHaveConfirmedStatus() {
        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("reservationBatchId는 UUID 타입으로 정상 저장된다")
    void createReservation_ShouldStoreReservationBatchIdAsUuid() {
        // given
        UUID batchId = UUID.randomUUID();

        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(batchId)
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        // then
        assertThat(reservation.getReservationBatchId()).isInstanceOf(UUID.class);
        assertThat(reservation.getReservationBatchId()).isEqualTo(batchId);
    }

    @Test
    @DisplayName("Reservation은 전달받은 User와 Resource와 연결된다")
    void createReservation_ShouldLinkGivenUserAndResource() {
        // given
        User user = createUser();
        Resource resource = createResource();

        // when
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        // then
        assertThat(reservation.getUser()).isSameAs(user);
        assertThat(reservation.getResource()).isSameAs(resource);
    }

    @Test
    @DisplayName("user가 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullUser_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(null)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User");
    }

    @Test
    @DisplayName("resource가 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullResource_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(null)
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource");
    }

    @Test
    @DisplayName("reservationBatchId가 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullReservationBatchId_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(null)
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservationBatchId");
    }

    @Test
    @DisplayName("startAt이 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullStartAt_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(null)
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startAt");
    }

    @Test
    @DisplayName("endAt이 null이면 Reservation 생성에 실패한다")
    void createReservation_WithNullEndAt_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endAt");
    }

    @Test
    @DisplayName("startAt이 endAt과 같으면 Reservation 생성에 실패한다")
    void createReservation_WithStartAtEqualToEndAt_ShouldThrowException() {
        // given
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 12, 10, 0);

        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(sameTime)
                .endAt(sameTime)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startAt");
    }

    @Test
    @DisplayName("startAt이 endAt보다 이후이면 Reservation 생성에 실패한다")
    void createReservation_WithStartAtAfterEndAt_ShouldThrowException() {
        assertThatThrownBy(() -> Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 30))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startAt");
    }

    @Test
    @DisplayName("동일한 reservationBatchId를 가진 여러 Reservation을 생성할 수 있다")
    void createMultipleReservations_WithSameBatchId_ShouldShareBatchId() {
        // given
        UUID batchId = UUID.randomUUID();
        User user = createUser();

        // when
        Reservation first = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 10, 15))
                .build();

        Reservation second = Reservation.builder()
                .reservationBatchId(batchId)
                .user(user)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 11, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 11, 15))
                .build();

        // then
        assertThat(first.getReservationBatchId()).isEqualTo(batchId);
        assertThat(second.getReservationBatchId()).isEqualTo(batchId);
    }
}
