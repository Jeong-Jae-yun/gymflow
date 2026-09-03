package com.gymflow.domain.waitingqueue.domain.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class WaitingQueueRedisRepository {

    // INCR로 뽑은 단조 증가 시퀀스를 그대로 ZADD의 score로 쓰고, 같은 스크립트 안에서 ZRANK까지
    // 반환한다. INCR + ZADD + ZRANK가 하나의 Lua 스크립트로 원자 실행되므로, 서로 다른 사용자의
    // 요청이 동시에 들어와도 "내가 ZADD한 직후, 내가 ZRANK를 조회하기 전에 다른 요청의 ZADD가
    // 끼어드는" 일이 없다 - 그 결과 score(=sequence) 동률도, rank 중복도 발생할 수 없다.
    private static final DefaultRedisScript<Long> ADD_AND_RANK_SCRIPT = new DefaultRedisScript<>(
            "local sequence = redis.call('INCR', KEYS[1]) "
                    + "redis.call('ZADD', KEYS[2], sequence, ARGV[1]) "
                    + "return redis.call('ZRANK', KEYS[2], ARGV[1])",
            Long.class);

    // ZREM 이후 큐가 완전히 비면(ZCARD == 0) sequence 카운터도 함께 정리한다. ZSET 자체는 Redis가
    // 마지막 멤버 제거 시 자동으로 삭제하지만, sequence는 별도의 String 키라 자동 삭제되지 않기
    // 때문에 명시적으로 지워준다. ZREM과 ZCARD 확인, 조건부 DEL을 한 스크립트로 묶어 그 사이에
    // 다른 요청의 ZADD(=재사용 시작)가 끼어들 여지를 없앤다.
    private static final DefaultRedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREM', KEYS[1], ARGV[1]) "
                    + "if redis.call('ZCARD', KEYS[1]) == 0 then redis.call('DEL', KEYS[2]) end "
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    // WaitingQueue를 큐(ZSET)에 추가하고, 그 자리에서 바로 1-based 순위 계산에 필요한 0-based
    // ZRANK를 반환한다. score는 더 이상 createdAt이 아니라 이 키 전용 시퀀스 카운터의 INCR 결과다.
    public Optional<Long> addAndGetRank(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String sequenceKey = WaitingQueueRedisKey.sequenceKeyFrom(resourceId, startAt);
        String queueKey = WaitingQueueRedisKey.from(resourceId, startAt);
        Long rank = redisTemplate.execute(
                ADD_AND_RANK_SCRIPT, List.of(sequenceKey, queueKey), String.valueOf(waitingQueueId));
        return Optional.ofNullable(rank);
    }

    // 이미 큐에 있는 멤버의 "현재" 순위만 다시 조회할 때 사용한다(예: 목록 조회 API). 추가를
    // 동반하지 않으므로 시퀀스를 다시 소비하지 않는다.
    public Optional<Long> rank(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String key = WaitingQueueRedisKey.from(resourceId, startAt);
        Long rank = redisTemplate.opsForZSet().rank(key, String.valueOf(waitingQueueId));
        return Optional.ofNullable(rank);
    }

    public void remove(Long waitingQueueId, Long resourceId, LocalDateTime startAt) {
        String queueKey = WaitingQueueRedisKey.from(resourceId, startAt);
        String sequenceKey = WaitingQueueRedisKey.sequenceKeyFrom(resourceId, startAt);
        redisTemplate.execute(REMOVE_SCRIPT, List.of(queueKey, sequenceKey), String.valueOf(waitingQueueId));
    }

    public List<Long> findAll(Long resourceId, LocalDateTime startAt) {
        String key = WaitingQueueRedisKey.from(resourceId, startAt);
        Set<String> members = redisTemplate.opsForZSet().range(key, 0, -1);
        if (members == null) {
            return List.of();
        }
        return members.stream().map(Long::valueOf).toList();
    }
}
