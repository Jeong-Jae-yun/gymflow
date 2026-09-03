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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

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
    @DisplayName("WAITING 등록 시 Redis Sorted Set에 추가되고 그 자리에서 rank가 반환된다")
    void addAndGetRank_ShouldRegisterMemberAndReturnRank() {
        // when
        Optional<Long> rank = waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);

        // then
        assertThat(rank).contains(0L);
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).contains(0L);
    }

    @Test
    @DisplayName("먼저 addAndGetRank를 호출한 WaitingQueue가 더 높은 순위(낮은 rank)를 갖는다 " +
            "(score는 createdAt이 아니라 호출 순서를 반영하는 시퀀스다)")
    void addAndGetRank_ShouldOrderBySequenceNotCreatedAt() {
        // when: 두 번째로 등록되는 멤버(2L)가, 실제로는 더 이른 시각을 의미할 법한 값을 갖고 있더라도
        // score는 오직 "호출 순서"로만 결정된다는 것을 보여주기 위해 먼저 2L을 등록한다.
        Optional<Long> rankOfFirstCall = waitingQueueRedisRepository.addAndGetRank(2L, RESOURCE_ID, START_AT);
        Optional<Long> rankOfSecondCall = waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);

        // then: 먼저 호출된 2L이 rank 0(1등), 나중에 호출된 1L이 rank 1(2등)이다.
        assertThat(rankOfFirstCall).contains(0L);
        assertThat(rankOfSecondCall).contains(1L);
        assertThat(waitingQueueRedisRepository.rank(2L, RESOURCE_ID, START_AT)).contains(0L);
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).contains(1L);
    }

    @Test
    @DisplayName("동일 Resource와 startAt에서 호출 순서대로 1, 2, 3위 rank가 반환된다")
    void addAndGetRank_WithThreeMembers_ShouldReturnFifoOrder() {
        // given & when
        Optional<Long> rank100 = waitingQueueRedisRepository.addAndGetRank(100L, RESOURCE_ID, START_AT);
        Optional<Long> rank101 = waitingQueueRedisRepository.addAndGetRank(101L, RESOURCE_ID, START_AT);
        Optional<Long> rank102 = waitingQueueRedisRepository.addAndGetRank(102L, RESOURCE_ID, START_AT);

        // then
        assertThat(rank100).contains(0L);
        assertThat(rank101).contains(1L);
        assertThat(rank102).contains(2L);
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).containsExactly(100L, 101L, 102L);
    }

    @Test
    @DisplayName("ZREM으로 제거하면 이후 rank 조회 결과가 없다")
    void remove_ShouldMakeRankLookupEmpty() {
        // given
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);

        // when
        waitingQueueRedisRepository.remove(1L, RESOURCE_ID, START_AT);

        // then
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("큐의 마지막 멤버를 제거하면 sequence key도 함께 삭제된다")
    void remove_WithLastMember_ShouldDeleteSequenceKey() {
        // given
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);
        String sequenceKey = WaitingQueueRedisKey.sequenceKeyFrom(RESOURCE_ID, START_AT);
        assertThat(redisTemplate.hasKey(sequenceKey)).isTrue();

        // when
        waitingQueueRedisRepository.remove(1L, RESOURCE_ID, START_AT);

        // then
        assertThat(redisTemplate.hasKey(sequenceKey)).isFalse();
    }

    @Test
    @DisplayName("큐에 멤버가 남아있으면 일부를 제거해도 sequence key는 유지된다")
    void remove_WithRemainingMembers_ShouldKeepSequenceKey() {
        // given
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);
        waitingQueueRedisRepository.addAndGetRank(2L, RESOURCE_ID, START_AT);
        String sequenceKey = WaitingQueueRedisKey.sequenceKeyFrom(RESOURCE_ID, START_AT);

        // when
        waitingQueueRedisRepository.remove(1L, RESOURCE_ID, START_AT);

        // then
        assertThat(redisTemplate.hasKey(sequenceKey)).isTrue();
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).containsExactly(2L);
    }

    @Test
    @DisplayName("큐가 비어 sequence key가 삭제된 뒤 다시 등록하면 시퀀스가 이어지지 않고 새로 시작한다")
    void addAndGetRank_AfterQueueDrainedAndSequenceDeleted_ShouldRestartSequence() {
        // given: 큐가 완전히 비어 sequence key가 삭제된 상태
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);
        waitingQueueRedisRepository.remove(1L, RESOURCE_ID, START_AT);
        assertThat(redisTemplate.hasKey(WaitingQueueRedisKey.sequenceKeyFrom(RESOURCE_ID, START_AT))).isFalse();

        // when: 새로 등록한다 - 큐가 비어있던 시점이므로 시퀀스가 1부터 다시 시작해도
        // 현재 큐에 있는 멤버들 사이의 순서 정합성에는 영향이 없다(다른 멤버가 없으므로 rank 0=1등).
        Optional<Long> rank = waitingQueueRedisRepository.addAndGetRank(2L, RESOURCE_ID, START_AT);

        // then
        assertThat(rank).contains(0L);
    }

    @Test
    @DisplayName("서로 다른 Resource는 서로 다른 Redis Key를 사용하여 순번이 섞이지 않는다")
    void addAndGetRank_WithDifferentResources_ShouldUseDifferentKeys() {
        // given
        Long otherResourceId = 202L;

        // when
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);
        waitingQueueRedisRepository.addAndGetRank(1L, otherResourceId, START_AT);

        // then
        assertThat(waitingQueueRedisRepository.rank(1L, RESOURCE_ID, START_AT)).contains(0L);
        assertThat(waitingQueueRedisRepository.rank(1L, otherResourceId, START_AT)).contains(0L);
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).containsExactly(1L);
        assertThat(waitingQueueRedisRepository.findAll(otherResourceId, START_AT)).containsExactly(1L);
    }

    @Test
    @DisplayName("서로 다른 startAt은 서로 다른 Redis Key를 사용하여 순번이 섞이지 않는다")
    void addAndGetRank_WithDifferentStartAt_ShouldUseDifferentKeys() {
        // given
        LocalDateTime otherStartAt = START_AT.plusHours(1);

        // when
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, otherStartAt);

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
    @DisplayName("등록 후 일부를 제거해도 FIFO 순서가 유지된다")
    void addAndGetRank_WithMultipleMembersOnSameKey_ShouldPreserveFifoOrderInFindAll() {
        // given
        waitingQueueRedisRepository.addAndGetRank(1L, RESOURCE_ID, START_AT);
        waitingQueueRedisRepository.addAndGetRank(2L, RESOURCE_ID, START_AT);
        waitingQueueRedisRepository.addAndGetRank(3L, RESOURCE_ID, START_AT);

        // when
        waitingQueueRedisRepository.remove(2L, RESOURCE_ID, START_AT);

        // then
        List<Long> remaining = waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT);
        assertThat(remaining).containsExactly(1L, 3L);
        assertThat(waitingQueueRedisRepository.rank(3L, RESOURCE_ID, START_AT)).contains(1L);
    }

    @Test
    @DisplayName("서로 다른 waitingQueueId 20개가 동시에 addAndGetRank를 호출해도 " +
            "rank가 정확히 0..19로 중복/누락 없이 배정된다 (Lua 원자화로 add+rank race를 제거)")
    void addAndGetRank_WithConcurrentDifferentMembers_ShouldAssignUniqueContiguousRanks() throws InterruptedException {
        // given
        int memberCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(memberCount);
        CountDownLatch readyLatch = new CountDownLatch(memberCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(memberCount);
        List<Long> observedRanks = new CopyOnWriteArrayList<>();

        // when: 서로 다른 waitingQueueId(1L..20L)가 동일 큐에 정확히 같은 순간을 노려 동시에 등록된다
        IntStream.rangeClosed(1, memberCount).forEach(id -> executor.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();
                Optional<Long> rank = waitingQueueRedisRepository.addAndGetRank((long) id, RESOURCE_ID, START_AT);
                rank.ifPresent(observedRanks::add);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        }));
        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 관측된 rank 20개가 정확히 {0, 1, ..., 19}와 일치한다 - 중복도 누락도 없다.
        assertThat(completed).isTrue();
        assertThat(observedRanks).hasSize(memberCount);
        Set<Long> uniqueRanks = Set.copyOf(observedRanks);
        List<Long> expectedRanks = LongStream.range(0, memberCount).boxed().toList();
        assertThat(uniqueRanks).hasSize(memberCount);
        assertThat(uniqueRanks).containsExactlyInAnyOrderElementsOf(expectedRanks);

        // and: Redis ZSET에도 20명 전원이 정확히 한 번씩만 등록되어 있다.
        assertThat(waitingQueueRedisRepository.findAll(RESOURCE_ID, START_AT)).hasSize(memberCount);
    }
}
