package com.gymflow.domain.resource.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.response.ResourceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Testcontainers Redis + MySQL을 사용해 Resource 상세 조회의 Cache-Aside 흐름
 * (MySQL 조회 -> Redis 생성 -> Cache Hit -> Evict -> 재생성)이 실제로 동작하는지 검증한다.
 * Redis 장애 시 fail-open 동작은 여러 SpringBootTest가 공유하는 Testcontainers Redis
 * 컨테이너를 직접 중단시키는 위험을 피하기 위해, 실제 컨테이너 대신 Mock으로 장애를
 * 재현하는 ResourceServiceTest에서 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResourceCacheIntegrationTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ResourceCacheRepository resourceCacheRepository;

    private Resource persistResourceWithPolicy(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(25)
                .build();
        return resourceRepository.save(resource);
    }

    @Test
    @DisplayName("최초 조회는 MySQL을 거쳐 Redis Cache를 생성하고, 이후 조회는 Cache Hit으로 응답한다")
    void getResourceDetail_ShouldPopulateCacheOnFirstCallAndHitOnSecondCall() {
        // given
        Resource resource = persistResourceWithPolicy("Cache E2E Resource " + System.nanoTime());
        assertThat(resourceCacheRepository.exists(resource.getId())).isFalse();

        // when: 최초 조회 (Cache MISS -> MySQL 조회 -> Redis 저장)
        ResourceResponse first = resourceService.getResourceDetail(resource.getId());

        // then: Redis에 동일한 응답이 캐시된다
        assertThat(resourceCacheRepository.exists(resource.getId())).isTrue();
        Optional<ResourceResponse> cached = resourceCacheRepository.get(resource.getId());
        assertThat(cached).contains(first);

        // when: 두 번째 조회 (Cache HIT)
        ResourceResponse second = resourceService.getResourceDetail(resource.getId());

        // then: Cache Hit으로 동일한 응답을 반환한다
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("Cache를 삭제한 뒤 다시 조회하면 MySQL 조회를 거쳐 Cache가 재생성된다")
    void getResourceDetail_AfterEvict_ShouldRebuildCacheFromMySql() {
        // given
        Resource resource = persistResourceWithPolicy("Cache E2E Resource " + System.nanoTime());
        resourceService.getResourceDetail(resource.getId());
        assertThat(resourceCacheRepository.exists(resource.getId())).isTrue();

        // when
        resourceCacheRepository.evict(resource.getId());
        assertThat(resourceCacheRepository.exists(resource.getId())).isFalse();
        ResourceResponse rebuilt = resourceService.getResourceDetail(resource.getId());

        // then
        assertThat(resourceCacheRepository.exists(resource.getId())).isTrue();
        assertThat(rebuilt.id()).isEqualTo(resource.getId());
        assertThat(rebuilt.reservationPolicy().maxDuration()).isEqualTo(25);
    }
}
