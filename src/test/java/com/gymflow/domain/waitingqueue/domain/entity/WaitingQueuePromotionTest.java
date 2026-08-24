package com.gymflow.domain.waitingqueue.domain.entity;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingQueuePromotionTest {

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

    private WaitingQueue createWaitingQueue() {
        return WaitingQueue.builder()
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
    }

    private WaitingQueuePromotion createOfferedPromotion() {
        return WaitingQueuePromotion.builder()
                .waitingQueue(createWaitingQueue())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .offeredAt(LocalDateTime.of(2026, 8, 12, 13, 58))
                .expiresAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .build();
    }

    @Test
    @DisplayName("WaitingQueuePromotion은 생성 시 OFFERED 상태이고 offeredAt/expiresAt이 저장된다")
    void createPromotion_WithValidData_ShouldBeOffered() {
        // given
        WaitingQueue waitingQueue = createWaitingQueue();
        User user = createUser();
        Resource resource = createResource();
        LocalDateTime offeredAt = LocalDateTime.of(2026, 8, 12, 13, 58);
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 12, 14, 0);

        // when
        WaitingQueuePromotion promotion = WaitingQueuePromotion.builder()
                .waitingQueue(waitingQueue)
                .user(user)
                .resource(resource)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .offeredAt(offeredAt)
                .expiresAt(expiresAt)
                .build();

        // then
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.OFFERED);
        assertThat(promotion.getWaitingQueue()).isSameAs(waitingQueue);
        assertThat(promotion.getUser()).isSameAs(user);
        assertThat(promotion.getResource()).isSameAs(resource);
        assertThat(promotion.getOfferedAt()).isEqualTo(offeredAt);
        assertThat(promotion.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(promotion.getAcceptedAt()).isNull();
        assertThat(promotion.getRejectedAt()).isNull();
        assertThat(promotion.getExpiredAt()).isNull();
    }

    @Test
    @DisplayName("waitingQueue가 null이면 생성에 실패한다")
    void createPromotion_WithNullWaitingQueue_ShouldThrowException() {
        assertThatThrownBy(() -> WaitingQueuePromotion.builder()
                .waitingQueue(null)
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .offeredAt(LocalDateTime.of(2026, 8, 12, 13, 58))
                .expiresAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WaitingQueue");
    }

    @Test
    @DisplayName("user가 null이면 생성에 실패한다")
    void createPromotion_WithNullUser_ShouldThrowException() {
        assertThatThrownBy(() -> WaitingQueuePromotion.builder()
                .waitingQueue(createWaitingQueue())
                .user(null)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .offeredAt(LocalDateTime.of(2026, 8, 12, 13, 58))
                .expiresAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User");
    }

    @Test
    @DisplayName("resource가 null이면 생성에 실패한다")
    void createPromotion_WithNullResource_ShouldThrowException() {
        assertThatThrownBy(() -> WaitingQueuePromotion.builder()
                .waitingQueue(createWaitingQueue())
                .user(createUser())
                .resource(null)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .offeredAt(LocalDateTime.of(2026, 8, 12, 13, 58))
                .expiresAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource");
    }

    @Test
    @DisplayName("startAt이 endAt보다 이후이면 생성에 실패한다")
    void createPromotion_WithStartAtAfterEndAt_ShouldThrowException() {
        assertThatThrownBy(() -> WaitingQueuePromotion.builder()
                .waitingQueue(createWaitingQueue())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .offeredAt(LocalDateTime.of(2026, 8, 12, 13, 58))
                .expiresAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("offeredAt이 expiresAt보다 이후이거나 같으면 생성에 실패한다")
    void createPromotion_WithOfferedAtNotBeforeExpiresAt_ShouldThrowException() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 12, 13, 58);

        assertThatThrownBy(() -> WaitingQueuePromotion.builder()
                .waitingQueue(createWaitingQueue())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .offeredAt(sameTime)
                .expiresAt(sameTime)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("OFFERED 상태의 Promotion은 수락하면 ACCEPTED가 되고 acceptedAt이 저장된다")
    void accept_WithOfferedStatus_ShouldChangeToAccepted() {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 13, 59);

        // when
        promotion.accept(now);

        // then
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.ACCEPTED);
        assertThat(promotion.getAcceptedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("OFFERED 상태의 Promotion은 거절하면 REJECTED가 되고 rejectedAt이 저장된다")
    void reject_WithOfferedStatus_ShouldChangeToRejected() {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 13, 59);

        // when
        promotion.reject(now);

        // then
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.REJECTED);
        assertThat(promotion.getRejectedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("OFFERED 상태의 Promotion은 만료 처리하면 EXPIRED가 되고 expiredAt이 저장된다")
    void expire_WithOfferedStatus_ShouldChangeToExpired() {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 14, 0);

        // when
        promotion.expire(now);

        // then
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.EXPIRED);
        assertThat(promotion.getExpiredAt()).isEqualTo(now);
    }

    private Reservation createReservation() {
        return Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
    }

    @Test
    @DisplayName("ACCEPTED 상태의 Promotion은 Reservation과 연결할 수 있다")
    void linkReservation_WithAcceptedPromotion_ShouldLinkReservation() {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        promotion.accept(LocalDateTime.of(2026, 8, 12, 13, 59));
        Reservation reservation = createReservation();

        // when
        promotion.linkReservation(reservation);

        // then
        assertThat(promotion.getReservation()).isSameAs(reservation);
    }

    @ParameterizedTest
    @EnumSource(value = PromotionStatus.class, names = {"OFFERED", "REJECTED", "EXPIRED"})
    @DisplayName("ACCEPTED 상태가 아니면 Reservation과 연결할 수 없다")
    void linkReservation_WithNonAcceptedPromotion_ShouldThrowException(PromotionStatus status) {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        if (status != PromotionStatus.OFFERED) {
            ReflectionTestUtils.setField(promotion, "status", status);
        }
        Reservation reservation = createReservation();

        // when & then
        assertThatThrownBy(() -> promotion.linkReservation(reservation))
                .isInstanceOf(IllegalStateException.class);
        assertThat(promotion.getReservation()).isNull();
    }

    @Test
    @DisplayName("연결할 Reservation이 null이면 예외가 발생한다")
    void linkReservation_WithNullReservation_ShouldThrowException() {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        promotion.accept(LocalDateTime.of(2026, 8, 12, 13, 59));

        // when & then
        assertThatThrownBy(() -> promotion.linkReservation(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이미 Reservation과 연결된 Promotion은 다시 연결할 수 없다")
    void linkReservation_WhenAlreadyLinked_ShouldThrowException() {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        promotion.accept(LocalDateTime.of(2026, 8, 12, 13, 59));
        promotion.linkReservation(createReservation());

        // when & then
        assertThatThrownBy(() -> promotion.linkReservation(createReservation()))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PromotionStatus.class, names = {"ACCEPTED", "REJECTED", "EXPIRED"})
    @DisplayName("terminal 상태(ACCEPTED/REJECTED/EXPIRED)의 Promotion은 다시 수락할 수 없다")
    void accept_WithTerminalStatus_ShouldThrowException(PromotionStatus status) {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        ReflectionTestUtils.setField(promotion, "status", status);

        // when & then
        assertThatThrownBy(() -> promotion.accept(LocalDateTime.of(2026, 8, 12, 13, 59)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(promotion.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = PromotionStatus.class, names = {"ACCEPTED", "REJECTED", "EXPIRED"})
    @DisplayName("terminal 상태(ACCEPTED/REJECTED/EXPIRED)의 Promotion은 다시 거절할 수 없다")
    void reject_WithTerminalStatus_ShouldThrowException(PromotionStatus status) {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        ReflectionTestUtils.setField(promotion, "status", status);

        // when & then
        assertThatThrownBy(() -> promotion.reject(LocalDateTime.of(2026, 8, 12, 13, 59)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(promotion.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = PromotionStatus.class, names = {"ACCEPTED", "REJECTED", "EXPIRED"})
    @DisplayName("terminal 상태(ACCEPTED/REJECTED/EXPIRED)의 Promotion은 다시 만료 처리할 수 없다")
    void expire_WithTerminalStatus_ShouldThrowException(PromotionStatus status) {
        // given
        WaitingQueuePromotion promotion = createOfferedPromotion();
        ReflectionTestUtils.setField(promotion, "status", status);

        // when & then
        assertThatThrownBy(() -> promotion.expire(LocalDateTime.of(2026, 8, 12, 14, 0)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(promotion.getStatus()).isEqualTo(status);
    }
}
