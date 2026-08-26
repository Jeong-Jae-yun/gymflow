package com.gymflow.domain.resource.domain.redis;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.dto.response.ReservationPolicySummaryResponse;
import com.gymflow.domain.resource.dto.response.ResourceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataRedisTest
@Import(TestcontainersConfiguration.class)
class ResourceCacheRepositoryTest {

    private static final Long RESOURCE_ID = 101L;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ResourceCacheRepository resourceCacheRepository;

    @BeforeEach
    void setUp() {
        resourceCacheRepository = new ResourceCacheRepository(redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private ResourceResponse sampleResponse() {
        return new ResourceResponse(
                RESOURCE_ID, "랫풀다운", ResourceType.MACHINE, ResourceStatus.ACTIVE, 1,
                new ReservationPolicySummaryResponse(15, 15, 25));
    }

    @Test
    @DisplayName("Cache 저장에 성공한다")
    void set_ShouldSucceed() {
        // when & then: 예외 없이 저장된다
        resourceCacheRepository.set(RESOURCE_ID, sampleResponse(), Duration.ofMinutes(10));
        assertThat(resourceCacheRepository.exists(RESOURCE_ID)).isTrue();
    }

    @Test
    @DisplayName("저장한 Cache를 조회하면 동일한 ResourceResponse가 반환된다")
    void get_AfterSet_ShouldReturnSameResponse() {
        // given
        ResourceResponse response = sampleResponse();
        resourceCacheRepository.set(RESOURCE_ID, response, Duration.ofMinutes(10));

        // when
        Optional<ResourceResponse> cached = resourceCacheRepository.get(RESOURCE_ID);

        // then
        assertThat(cached).contains(response);
    }

    @Test
    @DisplayName("존재하지 않는 Cache를 조회하면 빈 Optional을 반환한다")
    void get_WithUnregisteredResource_ShouldReturnEmpty() {
        // when & then
        assertThat(resourceCacheRepository.get(999L)).isEmpty();
        assertThat(resourceCacheRepository.exists(999L)).isFalse();
    }

    @Test
    @DisplayName("set 시 지정한 TTL이 Redis에 반영된다")
    void set_ShouldApplyGivenTtl() {
        // when
        resourceCacheRepository.set(RESOURCE_ID, sampleResponse(), Duration.ofMinutes(10));

        // then
        Long expireSeconds = redisTemplate.getExpire(ResourceCacheKey.from(RESOURCE_ID), TimeUnit.SECONDS);
        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isBetween(1L, Duration.ofMinutes(10).toSeconds());
    }

    @Test
    @DisplayName("Cache 삭제에 성공한다")
    void evict_ShouldDeleteKey() {
        // given
        resourceCacheRepository.set(RESOURCE_ID, sampleResponse(), Duration.ofMinutes(10));

        // when
        resourceCacheRepository.evict(RESOURCE_ID);

        // then
        assertThat(resourceCacheRepository.exists(RESOURCE_ID)).isFalse();
        assertThat(resourceCacheRepository.get(RESOURCE_ID)).isEmpty();
    }

    @Test
    @DisplayName("서로 다른 resourceId는 서로 다른 Key를 사용한다")
    void set_WithDifferentResourceIds_ShouldUseDifferentKeys() {
        // when
        resourceCacheRepository.set(RESOURCE_ID, sampleResponse(), Duration.ofMinutes(10));

        // then
        assertThat(resourceCacheRepository.exists(RESOURCE_ID)).isTrue();
        assertThat(resourceCacheRepository.exists(202L)).isFalse();
    }

    @Test
    @DisplayName("TTL이 지나면 Cache가 자동으로 사라진다")
    void set_AfterTtlExpires_ShouldNoLongerExist() throws InterruptedException {
        // given
        resourceCacheRepository.set(RESOURCE_ID, sampleResponse(), Duration.ofMillis(300));
        assertThat(resourceCacheRepository.exists(RESOURCE_ID)).isTrue();

        // when
        Thread.sleep(600);

        // then
        assertThat(resourceCacheRepository.exists(RESOURCE_ID)).isFalse();
    }
}
