package com.gymflow.domain.resource.domain.redis;

import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataRedisTest
@Import(TestcontainersConfiguration.class)
class ResourceRankingRedisRepositoryTest {

    private static final Long RESOURCE_101 = 101L;
    private static final Long RESOURCE_102 = 102L;
    private static final Long RESOURCE_103 = 103L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ResourceRankingRedisRepository resourceRankingRedisRepository;

    @BeforeEach
    void setUp() {
        resourceRankingRedisRepository = new ResourceRankingRedisRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("예약 생성 성공을 기록하면 score가 1 증가한다")
    void incrementReservationCount_ShouldIncreaseScoreByOne() {
        // when
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);

        // then
        assertThat(resourceRankingRedisRepository.findScore(RESOURCE_101)).contains(1L);
    }

    @Test
    @DisplayName("여러 번 증가시키면 score가 누적된다")
    void incrementReservationCount_WithMultipleCalls_ShouldAccumulateScore() {
        // when
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);

        // then
        assertThat(resourceRankingRedisRepository.findScore(RESOURCE_101)).contains(3L);
    }

    @Test
    @DisplayName("서로 다른 Resource는 독립적으로 score가 누적된다")
    void incrementReservationCount_WithDifferentResources_ShouldNotAffectEachOther() {
        // when
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_102);
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_102);

        // then
        assertThat(resourceRankingRedisRepository.findScore(RESOURCE_101)).contains(3L);
        assertThat(resourceRankingRedisRepository.findScore(RESOURCE_102)).contains(2L);
    }

    @Test
    @DisplayName("findTopResources는 score가 높은 순서대로 반환한다")
    void findTopResources_ShouldReturnResourcesOrderedByScoreDescending() {
        // given
        incrementBy(RESOURCE_101, 10);
        incrementBy(RESOURCE_102, 20);
        incrementBy(RESOURCE_103, 15);

        // when
        List<ResourceRankingRedisRepository.RankedResource> top3 = resourceRankingRedisRepository.findTopResources(3);

        // then
        assertThat(top3).extracting(ResourceRankingRedisRepository.RankedResource::resourceId)
                .containsExactly(RESOURCE_102, RESOURCE_103, RESOURCE_101);
        assertThat(top3).extracting(ResourceRankingRedisRepository.RankedResource::score)
                .containsExactly(20L, 15L, 10L);
    }

    @Test
    @DisplayName("findRank는 0-base rank를 반환하며, score가 높을수록 순위가 높다(rank가 낮다)")
    void findRank_ShouldReturnZeroBasedRankOrderedByScoreDescending() {
        // given
        incrementBy(RESOURCE_101, 10);
        incrementBy(RESOURCE_102, 20);
        incrementBy(RESOURCE_103, 15);

        // when & then: Redis 내부 rank는 0-base이므로 API/Repository 호출부에서 +1 하여 1위부터 표현해야 한다
        assertThat(resourceRankingRedisRepository.findRank(RESOURCE_102)).contains(0L);
        assertThat(resourceRankingRedisRepository.findRank(RESOURCE_103)).contains(1L);
        assertThat(resourceRankingRedisRepository.findRank(RESOURCE_101)).contains(2L);
    }

    @Test
    @DisplayName("findScore는 특정 Resource의 누적 예약 생성 횟수를 정확히 반환한다")
    void findScore_ShouldReturnAccurateAccumulatedCount() {
        // given
        incrementBy(RESOURCE_101, 37);

        // when & then
        assertThat(resourceRankingRedisRepository.findScore(RESOURCE_101)).contains(37L);
    }

    @Test
    @DisplayName("등록되지 않은 Resource의 rank/score 조회 시 빈 Optional을 반환한다")
    void findRankAndScore_WithUnregisteredResource_ShouldReturnEmpty() {
        // when & then
        assertThat(resourceRankingRedisRepository.findRank(999L)).isEqualTo(Optional.empty());
        assertThat(resourceRankingRedisRepository.findScore(999L)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("한 Resource의 Ranking을 증가시켜도 다른 Resource의 Ranking에는 영향을 주지 않는다")
    void incrementReservationCount_ShouldNotAffectOtherResourcesRankOrScore() {
        // given
        incrementBy(RESOURCE_101, 5);
        incrementBy(RESOURCE_102, 5);

        // when
        resourceRankingRedisRepository.incrementReservationCount(RESOURCE_101);

        // then
        assertThat(resourceRankingRedisRepository.findScore(RESOURCE_101)).contains(6L);
        assertThat(resourceRankingRedisRepository.findScore(RESOURCE_102)).contains(5L);
    }

    private void incrementBy(Long resourceId, int times) {
        for (int i = 0; i < times; i++) {
            resourceRankingRedisRepository.incrementReservationCount(resourceId);
        }
    }
}
