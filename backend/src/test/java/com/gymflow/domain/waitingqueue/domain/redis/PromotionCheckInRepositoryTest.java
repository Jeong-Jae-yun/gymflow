package com.gymflow.domain.waitingqueue.domain.redis;

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
class PromotionCheckInRepositoryTest {

    private static final Long RESERVATION_ID = 9001L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private PromotionCheckInRepository promotionCheckInRepository;

    @BeforeEach
    void setUp() {
        promotionCheckInRepository = new PromotionCheckInRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("register하면 Key가 생성되고 exists()는 true를 반환한다")
    void register_ShouldCreateKeyAndBeFoundByExists() {
        // when
        promotionCheckInRepository.register(RESERVATION_ID, Duration.ofMinutes(1));

        // then
        assertThat(promotionCheckInRepository.exists(RESERVATION_ID)).isTrue();
    }

    @Test
    @DisplayName("register 시 지정한 TTL이 Redis에 반영된다")
    void register_ShouldApplyGivenTtl() {
        // when
        promotionCheckInRepository.register(RESERVATION_ID, Duration.ofMinutes(1));

        // then
        Long expireSeconds = redisTemplate.getExpire(PromotionCheckInKey.from(RESERVATION_ID), TimeUnit.SECONDS);
        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isBetween(1L, Duration.ofMinutes(1).toSeconds());
    }

    @Test
    @DisplayName("등록되지 않은 reservationId에 대해 exists()는 false를 반환한다")
    void exists_WithUnregisteredReservation_ShouldReturnFalse() {
        // when & then
        assertThat(promotionCheckInRepository.exists(999L)).isFalse();
    }

    @Test
    @DisplayName("remove하면 Key가 삭제된다")
    void remove_ShouldDeleteKey() {
        // given
        promotionCheckInRepository.register(RESERVATION_ID, Duration.ofMinutes(1));

        // when
        promotionCheckInRepository.remove(RESERVATION_ID);

        // then
        assertThat(promotionCheckInRepository.exists(RESERVATION_ID)).isFalse();
    }

    @Test
    @DisplayName("서로 다른 reservationId는 서로 다른 Key를 사용한다")
    void register_WithDifferentReservationIds_ShouldUseDifferentKeys() {
        // when
        promotionCheckInRepository.register(9001L, Duration.ofMinutes(1));

        // then
        assertThat(promotionCheckInRepository.exists(9001L)).isTrue();
        assertThat(promotionCheckInRepository.exists(9002L)).isFalse();
    }

    @Test
    @DisplayName("TTL이 지나면 Key가 자동으로 사라진다")
    void register_AfterTtlExpires_ShouldNoLongerExist() throws InterruptedException {
        // given
        promotionCheckInRepository.register(RESERVATION_ID, Duration.ofMillis(300));
        assertThat(promotionCheckInRepository.exists(RESERVATION_ID)).isTrue();

        // when
        Thread.sleep(600);

        // then
        assertThat(promotionCheckInRepository.exists(RESERVATION_ID)).isFalse();
    }
}
