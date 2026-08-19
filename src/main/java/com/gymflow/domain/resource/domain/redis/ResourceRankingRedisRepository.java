package com.gymflow.domain.resource.domain.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class ResourceRankingRedisRepository {

    private final StringRedisTemplate redisTemplate;

    public void incrementReservationCount(Long resourceId) {
        redisTemplate.opsForZSet()
                .incrementScore(ResourceRankingKey.RESERVATION_RANKING, String.valueOf(resourceId), 1);
    }

    public List<RankedResource> findTopResources(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(ResourceRankingKey.RESERVATION_RANKING, 0, limit - 1);
        if (tuples == null) {
            return List.of();
        }
        return tuples.stream()
                .map(tuple -> new RankedResource(Long.valueOf(tuple.getValue()), tuple.getScore().longValue()))
                .toList();
    }

    public Optional<Long> findRank(Long resourceId) {
        Long rank = redisTemplate.opsForZSet()
                .reverseRank(ResourceRankingKey.RESERVATION_RANKING, String.valueOf(resourceId));
        return Optional.ofNullable(rank);
    }

    public Optional<Long> findScore(Long resourceId) {
        Double score = redisTemplate.opsForZSet()
                .score(ResourceRankingKey.RESERVATION_RANKING, String.valueOf(resourceId));
        return Optional.ofNullable(score).map(Double::longValue);
    }

    public record RankedResource(Long resourceId, Long score) {
    }
}
