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
class PromotionOfferedRepositoryTest {

    private static final Long PROMOTION_ID = 501L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private PromotionOfferedRepository promotionOfferedRepository;

    @BeforeEach
    void setUp() {
        promotionOfferedRepository = new PromotionOfferedRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("register하면 Key가 생성되고 exists()는 true를 반환한다")
    void register_ShouldCreateKeyAndBeFoundByExists() {
        // when
        promotionOfferedRepository.register(PROMOTION_ID, Duration.ofMinutes(2));

        // then
        assertThat(promotionOfferedRepository.exists(PROMOTION_ID)).isTrue();
    }

    @Test
    @DisplayName("register 시 지정한 TTL이 Redis에 반영된다")
    void register_ShouldApplyGivenTtl() {
        // when
        promotionOfferedRepository.register(PROMOTION_ID, Duration.ofMinutes(2));

        // then
        Long expireSeconds = redisTemplate.getExpire(PromotionOfferedKey.from(PROMOTION_ID), TimeUnit.SECONDS);
        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isBetween(1L, Duration.ofMinutes(2).toSeconds());
    }

    @Test
    @DisplayName("등록되지 않은 promotionId에 대해 exists()는 false를 반환한다")
    void exists_WithUnregisteredPromotion_ShouldReturnFalse() {
        // when & then
        assertThat(promotionOfferedRepository.exists(999L)).isFalse();
    }

    @Test
    @DisplayName("remove하면 Key가 삭제된다")
    void remove_ShouldDeleteKey() {
        // given
        promotionOfferedRepository.register(PROMOTION_ID, Duration.ofMinutes(2));

        // when
        promotionOfferedRepository.remove(PROMOTION_ID);

        // then
        assertThat(promotionOfferedRepository.exists(PROMOTION_ID)).isFalse();
    }

    @Test
    @DisplayName("서로 다른 promotionId는 서로 다른 Key를 사용한다")
    void register_WithDifferentPromotionIds_ShouldUseDifferentKeys() {
        // when
        promotionOfferedRepository.register(501L, Duration.ofMinutes(2));

        // then
        assertThat(promotionOfferedRepository.exists(501L)).isTrue();
        assertThat(promotionOfferedRepository.exists(502L)).isFalse();
    }

    @Test
    @DisplayName("TTL이 지나면 Key가 자동으로 사라진다")
    void register_AfterTtlExpires_ShouldNoLongerExist() throws InterruptedException {
        // given
        promotionOfferedRepository.register(PROMOTION_ID, Duration.ofMillis(300));
        assertThat(promotionOfferedRepository.exists(PROMOTION_ID)).isTrue();

        // when
        Thread.sleep(600);

        // then
        assertThat(promotionOfferedRepository.exists(PROMOTION_ID)).isFalse();
    }
}
