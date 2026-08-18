package com.gymflow.domain.resource.domain.redis;

import com.gymflow.domain.resource.dto.response.ResourceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ResourceCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<ResourceResponse> get(Long resourceId) {
        String key = ResourceCacheKey.from(resourceId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(value, ResourceResponse.class));
    }

    public void set(Long resourceId, ResourceResponse response, Duration ttl) {
        String key = ResourceCacheKey.from(resourceId);
        String value = objectMapper.writeValueAsString(response);
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public void evict(Long resourceId) {
        String key = ResourceCacheKey.from(resourceId);
        redisTemplate.delete(key);
    }

    public boolean exists(Long resourceId) {
        String key = ResourceCacheKey.from(resourceId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
