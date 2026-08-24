package com.gymflow.domain.waitingqueue.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueue;
import com.gymflow.domain.waitingqueue.domain.entity.WaitingQueuePromotion;
import com.gymflow.domain.waitingqueue.domain.enumtype.PromotionStatus;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class WaitingQueuePromotionRepositoryTest {

    @Autowired
    private WaitingQueuePromotionRepository promotionRepository;

    @Autowired
    private TestEntityManager entityManager;

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 12, 14, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 8, 12, 14, 15);

    private User persistUser(String email) {
        User user = User.builder()
                .email(email)
                .password("securePassword123")
                .name("Promotion Tester")
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

    private WaitingQueue persistWaitingQueue(User user, Resource resource) {
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        entityManager.persist(waitingQueue);
        return waitingQueue;
    }

    private WaitingQueuePromotion buildPromotion(WaitingQueue waitingQueue, User user, Resource resource) {
        return WaitingQueuePromotion.builder()
                .waitingQueue(waitingQueue)
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .offeredAt(LocalDateTime.of(2026, 8, 12, 13, 58))
                .expiresAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .build();
    }

    @Test
    @DisplayName("WaitingQueuePromotion은 WaitingQueue/User/Resource와 연결되어 실제 DB에 저장된다")
    void save_ShouldPersistPromotionWithAssociations() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(user, resource);
        WaitingQueuePromotion promotion = buildPromotion(waitingQueue, user, resource);

        // when
        WaitingQueuePromotion saved = promotionRepository.save(promotion);
        entityManager.flush();
        entityManager.clear();

        // then
        WaitingQueuePromotion reloaded = promotionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getWaitingQueue().getId()).isEqualTo(waitingQueue.getId());
        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
        assertThat(reloaded.getResource().getId()).isEqualTo(resource.getId());
        assertThat(reloaded.getStatus()).isEqualTo(PromotionStatus.OFFERED);
    }

    @Test
    @DisplayName("동일한 WaitingQueue에 대해 두 번째 Promotion을 저장하면 UNIQUE 제약 위반으로 실패한다")
    void save_WithDuplicateWaitingQueue_ShouldViolateUniqueConstraint() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(user, resource);
        promotionRepository.save(buildPromotion(waitingQueue, user, resource));
        entityManager.flush();

        WaitingQueuePromotion duplicate = buildPromotion(waitingQueue, user, resource);

        // when & then
        assertThatThrownBy(() -> {
            promotionRepository.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("existsByWaitingQueueId는 이미 Promotion이 존재하는 WaitingQueue에 대해 true를 반환한다")
    void existsByWaitingQueueId_WithExistingPromotion_ShouldReturnTrue() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(user, resource);
        promotionRepository.save(buildPromotion(waitingQueue, user, resource));
        entityManager.flush();

        // when
        boolean exists = promotionRepository.existsByWaitingQueueId(waitingQueue.getId());

        // then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByWaitingQueueId는 Promotion이 없는 WaitingQueue에 대해 false를 반환한다")
    void existsByWaitingQueueId_WithoutPromotion_ShouldReturnFalse() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(user, resource);
        entityManager.flush();

        // when
        boolean exists = promotionRepository.existsByWaitingQueueId(waitingQueue.getId());

        // then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("findByResourceIdAndStartAtAndEndAtAndStatus는 활성 OFFERED Promotion을 조회한다")
    void findByResourceIdAndStartAtAndEndAtAndStatus_WithActiveOffer_ShouldReturnPromotion() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(user, resource);
        WaitingQueuePromotion saved = promotionRepository.save(buildPromotion(waitingQueue, user, resource));
        entityManager.flush();

        // when
        Optional<WaitingQueuePromotion> found = promotionRepository.findByResourceIdAndStartAtAndEndAtAndStatus(
                resource.getId(), START_AT, END_AT, PromotionStatus.OFFERED);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findByResourceIdAndStartAtAndEndAtAndStatus는 ACCEPTED 등 다른 상태는 조회하지 않는다")
    void findByResourceIdAndStartAtAndEndAtAndStatus_WithAcceptedPromotion_ShouldReturnEmpty() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(user, resource);
        WaitingQueuePromotion promotion = promotionRepository.save(buildPromotion(waitingQueue, user, resource));
        promotion.accept(LocalDateTime.of(2026, 8, 12, 13, 59));
        entityManager.flush();

        // when
        Optional<WaitingQueuePromotion> found = promotionRepository.findByResourceIdAndStartAtAndEndAtAndStatus(
                resource.getId(), START_AT, END_AT, PromotionStatus.OFFERED);

        // then
        assertThat(found).isEmpty();
    }

    private Reservation persistReservation(User user, Resource resource) {
        Reservation reservation = Reservation.builder()
                .reservationBatchId(java.util.UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(START_AT)
                .endAt(END_AT)
                .build();
        entityManager.persist(reservation);
        return reservation;
    }

    @Test
    @DisplayName("findByReservationId는 ACCEPT로 연결된 Reservation을 기준으로 Promotion을 조회한다")
    void findByReservationId_WithLinkedReservation_ShouldReturnPromotion() {
        // given
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(user, resource);
        WaitingQueuePromotion promotion = promotionRepository.save(buildPromotion(waitingQueue, user, resource));
        Reservation reservation = persistReservation(user, resource);
        promotion.accept(LocalDateTime.of(2026, 8, 12, 13, 59));
        promotion.linkReservation(reservation);
        entityManager.flush();
        entityManager.clear();

        // when
        Optional<WaitingQueuePromotion> found = promotionRepository.findByReservationId(reservation.getId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(promotion.getId());
    }

    @Test
    @DisplayName("findByReservationId는 연결되지 않은 Reservation에 대해 빈 값을 반환한다")
    void findByReservationId_WithUnlinkedReservation_ShouldReturnEmpty() {
        // given: 일반 예약 흐름으로 생성된 Reservation은 어떤 Promotion과도 연결되지 않는다
        User user = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        Reservation reservation = persistReservation(user, resource);
        entityManager.flush();
        entityManager.clear();

        // when
        Optional<WaitingQueuePromotion> found = promotionRepository.findByReservationId(reservation.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndUserId는 본인 소유의 Promotion을 조회한다")
    void findByIdAndUserId_WithOwner_ShouldReturnPromotion() {
        // given
        User owner = persistUser("owner@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(owner, resource);
        WaitingQueuePromotion saved = promotionRepository.save(buildPromotion(waitingQueue, owner, resource));
        entityManager.flush();

        // when
        Optional<WaitingQueuePromotion> found = promotionRepository.findByIdAndUserId(saved.getId(), owner.getId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findByIdAndUserId는 다른 사용자의 Promotion을 조회하면 빈 값을 반환한다")
    void findByIdAndUserId_WithOtherUser_ShouldReturnEmpty() {
        // given
        User owner = persistUser("owner@gymflow.com");
        User other = persistUser("other@gymflow.com");
        Resource resource = persistResource("Chest Press A-1");
        WaitingQueue waitingQueue = persistWaitingQueue(owner, resource);
        WaitingQueuePromotion saved = promotionRepository.save(buildPromotion(waitingQueue, owner, resource));
        entityManager.flush();

        // when
        Optional<WaitingQueuePromotion> found = promotionRepository.findByIdAndUserId(saved.getId(), other.getId());

        // then
        assertThat(found).isEmpty();
    }
}
