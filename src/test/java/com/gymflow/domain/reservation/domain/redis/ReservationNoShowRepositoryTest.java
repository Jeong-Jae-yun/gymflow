package com.gymflow.domain.reservation.domain.redis;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataRedisTest
@Import(TestcontainersConfiguration.class)
class ReservationNoShowRepositoryTest {

    private static final Long RESERVATION_ID = 100L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ReservationNoShowRepository reservationNoShowRepository;

    @BeforeEach
    void setUp() {
        reservationNoShowRepository = new ReservationNoShowRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("register하면 Key가 생성되고 exists()는 true를 반환한다")
    void register_ShouldCreateKeyAndBeFoundByExists() {
        // when
        reservationNoShowRepository.register(RESERVATION_ID, Duration.ofMinutes(5));

        // then
        assertThat(reservationNoShowRepository.exists(RESERVATION_ID)).isTrue();
    }

    @Test
    @DisplayName("register 시 지정한 TTL이 Redis에 반영된다")
    void register_ShouldApplyGivenTtl() {
        // when
        reservationNoShowRepository.register(RESERVATION_ID, Duration.ofMinutes(5));

        // then
        Long expireSeconds = redisTemplate.getExpire(ReservationNoShowKey.from(RESERVATION_ID), TimeUnit.SECONDS);
        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isBetween(1L, Duration.ofMinutes(5).toSeconds());
    }

    @Test
    @DisplayName("등록되지 않은 reservationId에 대해 exists()는 false를 반환한다")
    void exists_WithUnregisteredReservation_ShouldReturnFalse() {
        // when & then
        assertThat(reservationNoShowRepository.exists(999L)).isFalse();
    }

    @Test
    @DisplayName("remove하면 Key가 삭제된다")
    void remove_ShouldDeleteKey() {
        // given
        reservationNoShowRepository.register(RESERVATION_ID, Duration.ofMinutes(5));

        // when
        reservationNoShowRepository.remove(RESERVATION_ID);

        // then
        assertThat(reservationNoShowRepository.exists(RESERVATION_ID)).isFalse();
    }

    @Test
    @DisplayName("서로 다른 reservationId는 서로 다른 Key를 사용한다")
    void register_WithDifferentReservationIds_ShouldUseDifferentKeys() {
        // when
        reservationNoShowRepository.register(100L, Duration.ofMinutes(5));

        // then
        assertThat(reservationNoShowRepository.exists(100L)).isTrue();
        assertThat(reservationNoShowRepository.exists(200L)).isFalse();
    }

    @Test
    @DisplayName("TTL이 지나면 Key가 자동으로 사라진다")
    void register_AfterTtlExpires_ShouldNoLongerExist() throws InterruptedException {
        // given
        reservationNoShowRepository.register(RESERVATION_ID, Duration.ofMillis(300));
        assertThat(reservationNoShowRepository.exists(RESERVATION_ID)).isTrue();

        // when
        Thread.sleep(600);

        // then
        assertThat(reservationNoShowRepository.exists(RESERVATION_ID)).isFalse();
    }
}
