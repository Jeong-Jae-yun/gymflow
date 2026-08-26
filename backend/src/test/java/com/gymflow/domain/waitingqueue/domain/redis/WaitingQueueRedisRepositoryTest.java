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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataRedisTest
@Import(TestcontainersConfiguration.class)
class WaitingQueueRedisRepositoryTest {

    private static final Long RESOURCE_ID = 101L;
    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 8, 20, 14, 0);

    @Autowired
    private StringRedisTemplate redisTemplate;

    private WaitingQueueRedisRepository waitingQueueRedisRepository;

    @BeforeEach
    void setUp() {
        waitingQueueRedisRepository = new WaitingQueueRedisRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("WAITING 등록 시 Redis Sorted Set에 추가되고 rank로 조회된다")
    void add_ShouldRegisterMemberAndBeFoundByRank() {
        // when
        waitingQueueRedisRepository.add(1L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 0));

        // then
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).contains(0L);
    }

    @Test
    @DisplayName("먼저 등록한 WaitingQueue가 더 높은 순위(낮은 rank)를 갖는다")
    void add_WithEarlierCreatedAt_ShouldHaveHigherPriority() {
        // given
        LocalDateTime firstCreatedAt = LocalDateTime.of(2026, 8, 18, 10, 0);
        LocalDateTime secondCreatedAt = LocalDateTime.of(2026, 8, 18, 10, 1);

        // when
        waitingQueueRedisRepository.add(2L, RESOURCE_ID, START_AT, secondCreatedAt);
        waitingQueueRedisRepository.add(1L, RESOURCE_ID, START_AT, firstCreatedAt);

        // then
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).contains(0L);
        assertThat(waitingQueueRedisRepository.rank(2L, RESOURCE_ID, START_AT)).contains(1L);
    }

    @Test
    @DisplayName("동일 Resource와 startAt에서 등록 순서대로 1, 2, 3위 rank가 반환된다")
    void rank_WithThreeMembers_ShouldReturnFifoOrder() {
        // given
        waitingQueueRedisRepository.add(100L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 0));
        waitingQueueRedisRepository.add(101L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 1));
        waitingQueueRedisRepository.add(102L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 2));

        // when & then
        assertThat(waitingQueueRedisRepository.rank(100L, RESOURCE_ID, START_AT)).contains(0L);
        assertThat(waitingQueueRedisRepository.rank(101L, RESOURCE_ID, START_AT)).contains(1L);
        assertThat(waitingQueueRedisRepository.rank(102L, RESOURCE_ID, START_AT)).contains(2L);
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).containsExactly(100L, 101L, 102L);
    }

    @Test
    @DisplayName("ZREM으로 제거하면 이후 rank 조회 결과가 없다")
    void remove_ShouldMakeRankLookupEmpty() {
        // given
        waitingQueueRedisRepository.add(1L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 0));

        // when
        waitingQueueRedisRepository.remove(1L, RESOURCE_ID, START_AT);

        // then
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("서로 다른 Resource는 서로 다른 Redis Key를 사용하여 순번이 섞이지 않는다")
    void add_WithDifferentResources_ShouldUseDifferentKeys() {
        // given
        Long otherResourceId = 202L;

        // when
        waitingQueueRedisRepository.add(1L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 0));
        waitingQueueRedisRepository.add(1L, otherResourceId, START_AT, LocalDateTime.of(2026, 8, 18, 9, 0));

        // then
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).contains(0L);
        assertThat(waitingQueueRedisRepository.rank(1L, otherResourceId, START_AT)).contains(0L);
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).containsExactly(1L);
        assertThat(waitingQueueRedisRepository.findAll(otherResourceId, START_AT)).containsExactly(1L);
    }

    @Test
    @DisplayName("서로 다른 startAt은 서로 다른 Redis Key를 사용하여 순번이 섞이지 않는다")
    void add_WithDifferentStartAt_ShouldUseDifferentKeys() {
        // given
        LocalDateTime otherStartAt = START_AT.plusHours(1);

        // when
        waitingQueueRedisRepository.add(1L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 0));
        waitingQueueRedisRepository.add(1L, RESOURCE_ID, otherStartAt, LocalDateTime.of(2026, 8, 18, 9, 0));

        // then
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).containsExactly(1L);
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, otherStartAt)).containsExactly(1L);
    }

    @Test
    @DisplayName("Redis에 존재하지 않는 waitingQueueId를 조회하면 빈 Optional을 반환한다")
    void rank_WithUnknownMember_ShouldReturnEmpty() {
        // when & then
        assertThat(waitingQueueRedisRepository.rank(999L, RESOURCE_ID, START_AT)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("동일 Resource와 startAt에서 재등록해도 FIFO 순서가 유지된다")
    void add_WithMultipleMembersOnSameKey_ShouldPreserveFifoOrderInFindAll() {
        // given
        waitingQueueRedisRepository.add(1L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 0));
        waitingQueueRedisRepository.add(2L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 5));
        waitingQueueRedisRepository.add(3L, RESOURCE_ID, START_AT, LocalDateTime.of(2026, 8, 18, 10, 10));

        // when
        waitingQueueRedisRepository.remove(2L, RESOURCE_ID, START_AT);

        // then
        List<Long> remaining = waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT);
        assertThat(remaining).containsExactly(1L, 3L);
        assertThat(waitingQueueRedisRepository.rank(3L, RESOURCE_ID, START_AT)).contains(1L);
    }
}
