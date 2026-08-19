package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository.RankedResource;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.response.PopularResourceResponse;
import com.gymflow.domain.resource.dto.response.ReservationPolicySummaryResponse;
import com.gymflow.domain.resource.dto.response.ResourceResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    private static final Long RESOURCE_ID = 10L;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ResourceCacheRepository resourceCacheRepository;

    @Mock
    private ResourceRankingRedisRepository resourceRankingRedisRepository;

    @InjectMocks
    private ResourceService resourceService;

    private Resource resource() {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .description("3F Weight Zone")
                .build();
        ReflectionTestUtils.setField(resource, "id", RESOURCE_ID);
        return resource;
    }

    private Resource resourceWithPolicy() {
        Resource resource = resource();
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(60)
                .build();
        return resource;
    }

    private Resource resourceWithIdAndStatus(Long id, ResourceStatus status) {
        Resource resource = Resource.builder()
                .name("Resource-" + id)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", id);
        ReflectionTestUtils.setField(resource, "status", status);
        return resource;
    }

    @Test
    @DisplayName("Resource 목록을 Page 형태로 조회한다")
    void getResources_ShouldReturnPageOfResourceResponse() {
        // given
        Resource resource = resourceWithPolicy();
        Pageable pageable = PageRequest.of(0, 10);
        Page<Resource> page = new PageImpl<>(List.of(resource), pageable, 1);
        when(resourceRepository.findAll(pageable)).thenReturn(page);

        // when
        Page<ResourceResponse> response = resourceService.getResources(pageable);

        // then
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).id()).isEqualTo(RESOURCE_ID);
        assertThat(response.getContent().get(0).resourceType()).isEqualTo(ResourceType.MACHINE);
        assertThat(response.getContent().get(0).reservationPolicy().maxDuration()).isEqualTo(60);
    }

    @Test
    @DisplayName("ReservationPolicy가 없는 Resource는 reservationPolicy가 null로 조회된다")
    void getResources_WithoutReservationPolicy_ShouldReturnNullPolicy() {
        // given
        Resource resource = resource();
        Pageable pageable = PageRequest.of(0, 10);
        Page<Resource> page = new PageImpl<>(List.of(resource), pageable, 1);
        when(resourceRepository.findAll(pageable)).thenReturn(page);

        // when
        Page<ResourceResponse> response = resourceService.getResources(pageable);

        // then
        assertThat(response.getContent().get(0).reservationPolicy()).isNull();
    }

    @Test
    @DisplayName("본인의 Resource 상세를 정상적으로 조회한다 (Cache MISS -> MySQL 조회 -> Redis 저장)")
    void getResourceDetail_WithExistingResource_ShouldReturnResponse() {
        // given: Cache MISS (미스텁 상태에서는 Optional.empty()가 기본값)
        Resource resource = resourceWithPolicy();
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        ResourceResponse response = resourceService.getResourceDetail(RESOURCE_ID);

        // then
        assertThat(response.id()).isEqualTo(RESOURCE_ID);
        assertThat(response.name()).isEqualTo("Chest Press A-1");
        assertThat(response.capacity()).isEqualTo(1);
        assertThat(response.reservationPolicy().slotDuration()).isEqualTo(15);
        verify(resourceCacheRepository).get(RESOURCE_ID);
        verify(resourceRepository).findWithReservationPolicyById(RESOURCE_ID);
        verify(resourceCacheRepository).set(eq(RESOURCE_ID), any(ResourceResponse.class), any(Duration.class));
    }

    @Test
    @DisplayName("Cache HIT이면 MySQL Repository를 호출하지 않고 캐시 데이터를 그대로 반환한다")
    void getResourceDetail_WithCacheHit_ShouldReturnCachedResponseWithoutQueryingMySql() {
        // given
        ResourceResponse cachedResponse = new ResourceResponse(
                RESOURCE_ID, "Chest Press A-1", ResourceType.MACHINE, ResourceStatus.ACTIVE, 1,
                new ReservationPolicySummaryResponse(15, 15, 60));
        when(resourceCacheRepository.get(RESOURCE_ID)).thenReturn(Optional.of(cachedResponse));

        // when
        ResourceResponse response = resourceService.getResourceDetail(RESOURCE_ID);

        // then
        assertThat(response).isEqualTo(cachedResponse);
        verify(resourceRepository, never()).findWithReservationPolicyById(any());
        verify(resourceCacheRepository, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("Redis GET이 실패해도 MySQL 조회로 정상 응답한다")
    void getResourceDetail_WithCacheGetFailure_ShouldFallBackToMySql() {
        // given
        Resource resource = resourceWithPolicy();
        doThrow(new RedisConnectionFailureException("연결 실패")).when(resourceCacheRepository).get(RESOURCE_ID);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        ResourceResponse response = resourceService.getResourceDetail(RESOURCE_ID);

        // then
        assertThat(response.id()).isEqualTo(RESOURCE_ID);
        verify(resourceRepository).findWithReservationPolicyById(RESOURCE_ID);
    }

    @Test
    @DisplayName("Redis SET이 실패해도 MySQL 조회 결과를 정상적으로 응답한다")
    void getResourceDetail_WithCacheSetFailure_ShouldStillReturnResponse() {
        // given
        Resource resource = resourceWithPolicy();
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(resourceCacheRepository).set(eq(RESOURCE_ID), any(ResourceResponse.class), any(Duration.class));

        // when
        ResourceResponse response = resourceService.getResourceDetail(RESOURCE_ID);

        // then
        assertThat(response.id()).isEqualTo(RESOURCE_ID);
        assertThat(response.reservationPolicy().maxDuration()).isEqualTo(60);
    }

    @Test
    @DisplayName("ReservationPolicy가 포함된 Response가 그대로 캐시 저장 대상으로 전달된다")
    void getResourceDetail_ShouldCacheResponseWithReservationPolicy() {
        // given
        Resource resource = resourceWithPolicy();
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        resourceService.getResourceDetail(RESOURCE_ID);

        // then
        ArgumentCaptor<ResourceResponse> captor = ArgumentCaptor.forClass(ResourceResponse.class);
        verify(resourceCacheRepository).set(eq(RESOURCE_ID), captor.capture(), any(Duration.class));
        assertThat(captor.getValue().reservationPolicy()).isNotNull();
        assertThat(captor.getValue().reservationPolicy().slotDuration()).isEqualTo(15);
        assertThat(captor.getValue().reservationPolicy().minDuration()).isEqualTo(15);
        assertThat(captor.getValue().reservationPolicy().maxDuration()).isEqualTo(60);
    }

    @Test
    @DisplayName("존재하지 않는 Resource를 조회하면 예외가 발생한다")
    void getResourceDetail_WithNonExistentResource_ShouldThrowException() {
        // given
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> resourceService.getResourceDetail(RESOURCE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("Redis Ranking 순서대로 인기 Resource 목록을 순위와 함께 반환한다")
    void getPopularResources_ShouldReturnResponsesOrderedByRank() {
        // given
        when(resourceRankingRedisRepository.findTopResources(3)).thenReturn(List.of(
                new RankedResource(102L, 20L),
                new RankedResource(103L, 15L),
                new RankedResource(101L, 10L)));
        when(resourceRepository.findAllById(List.of(102L, 103L, 101L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<PopularResourceResponse> popularResources = resourceService.getPopularResources(3);

        // then
        assertThat(popularResources).containsExactly(
                new PopularResourceResponse(102L, 20L, 1),
                new PopularResourceResponse(103L, 15L, 2),
                new PopularResourceResponse(101L, 10L, 3));
    }

    @Test
    @DisplayName("INACTIVE Resource는 인기 목록에서 제외되고, 남은 Resource의 순위는 그대로 유지된다")
    void getPopularResources_WithInactiveResource_ShouldExcludeItAndKeepOriginalRankForOthers() {
        // given
        when(resourceRankingRedisRepository.findTopResources(3)).thenReturn(List.of(
                new RankedResource(102L, 20L),
                new RankedResource(103L, 15L),
                new RankedResource(101L, 10L)));
        when(resourceRepository.findAllById(List.of(102L, 103L, 101L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.INACTIVE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<PopularResourceResponse> popularResources = resourceService.getPopularResources(3);

        // then: 2위(102)는 제외되고, 나머지는 원래 순위(2위->유지 X, 3위->3 유지)를 그대로 갖는다
        assertThat(popularResources).containsExactly(
                new PopularResourceResponse(103L, 15L, 2),
                new PopularResourceResponse(101L, 10L, 3));
    }

    @Test
    @DisplayName("존재하지 않는 Resource는 인기 목록에서 제외된다")
    void getPopularResources_WithDeletedResource_ShouldExcludeIt() {
        // given
        when(resourceRankingRedisRepository.findTopResources(2)).thenReturn(List.of(
                new RankedResource(101L, 10L),
                new RankedResource(999L, 5L)));
        when(resourceRepository.findAllById(List.of(101L, 999L)))
                .thenReturn(List.of(resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE)));

        // when
        List<PopularResourceResponse> popularResources = resourceService.getPopularResources(2);

        // then
        assertThat(popularResources).containsExactly(new PopularResourceResponse(101L, 10L, 1));
    }

    @Test
    @DisplayName("Redis Ranking 조회가 비어있으면 빈 목록을 반환하고 MySQL을 조회하지 않는다")
    void getPopularResources_WithEmptyRanking_ShouldReturnEmptyListWithoutQueryingMySql() {
        // given
        when(resourceRankingRedisRepository.findTopResources(10)).thenReturn(List.of());

        // when
        List<PopularResourceResponse> popularResources = resourceService.getPopularResources(10);

        // then
        assertThat(popularResources).isEmpty();
        verify(resourceRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("Redis Ranking 조회가 실패하면 빈 목록을 반환한다")
    void getPopularResources_WithRedisFailure_ShouldReturnEmptyList() {
        // given
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(resourceRankingRedisRepository).findTopResources(10);

        // when
        List<PopularResourceResponse> popularResources = resourceService.getPopularResources(10);

        // then
        assertThat(popularResources).isEmpty();
        verify(resourceRepository, never()).findAllById(any());
    }
}
