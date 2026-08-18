package com.gymflow.domain.reservation.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.redis.ReservationNoShowRepository;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 Testcontainers Redis + 실제 Spring 컨텍스트(RedisMessageListenerContainer,
 * ReservationNoShowListener)를 사용해 "Redis TTL 만료 -> 실제 Listener 동작 -> Reservation
 * 상태 변경"까지의 배선(wiring) 전체가 실제로 동작하는지 검증한다. Reservation.noShow(now)
 * 자체의 5분 그레이스 기간 불변식은 ReservationTest에서 이미 충분히 검증되어 있으므로,
 * 여기서는 실제 5분을 기다리는 대신 짧은 TTL을 직접 등록해 인프라 배선만 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationNoShowIntegrationTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReservationNoShowRepository reservationNoShowRepository;

    private User persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("NoShow E2E Tester")
                .build());
    }

    private Resource persistResource(String name) {
        return resourceRepository.save(Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build());
    }

    @Test
    @DisplayName("Redis NO_SHOW Key의 TTL이 만료되면 실제 Listener가 동작해 Reservation이 NO_SHOW로 전이된다")
    void noShowKeyExpiration_ShouldTransitionReservationToNoShowViaRealListener() {
        // given: startAt을 과거로 설정해 Reservation.noShow(now)의 그레이스 기간 불변식은
        // 이미 통과한 상태로 만들고, 실제 5분을 기다리지 않도록 짧은 TTL을 직접 등록한다
        User user = persistUser("noshow-e2e-1-" + System.nanoTime() + "@gymflow.com");
        Resource resource = persistResource("NoShow E2E Resource 1 " + System.nanoTime());
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(startAt.plusMinutes(15))
                .build());

        // when
        reservationNoShowRepository.register(reservation.getId(), Duration.ofMillis(500));

        // then
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Reservation reloaded = reservationRepository.findById(reservation.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        });
    }

    @Test
    @DisplayName("CHECKED_IN 상태의 Reservation은 Redis TTL이 만료되어도 NO_SHOW로 바뀌지 않는다")
    void noShowKeyExpiration_WithCheckedInReservation_ShouldNotTransition() {
        // given: TTL 만료 전에 정상적으로 체크인했지만 Redis Key 삭제가 실패한 상황을 재현한다
        User user = persistUser("noshow-e2e-2-" + System.nanoTime() + "@gymflow.com");
        Resource resource = persistResource("NoShow E2E Resource 2 " + System.nanoTime());
        LocalDateTime startAt = LocalDateTime.now().minusMinutes(10);
        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(startAt.plusMinutes(15))
                .build();
        reservation.checkIn();
        Reservation savedReservation = reservationRepository.save(reservation);

        // when
        reservationNoShowRepository.register(savedReservation.getId(), Duration.ofMillis(500));

        // then: 만료 이벤트가 처리될 시간을 충분히 준 뒤에도 상태가 유지되는지 확인한다
        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    Reservation reloaded = reservationRepository.findById(savedReservation.getId()).orElseThrow();
                    assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
                });
    }
}
