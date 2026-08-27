package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository.RankedResource;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.domain.storage.ResourceImageStorage;
import com.gymflow.domain.resource.dto.response.PopularResourceResponse;
import com.gymflow.domain.resource.dto.response.ReservationPolicySummaryResponse;
import com.gymflow.domain.resource.dto.response.ResourceRankingResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Mock
    private ResourceImageStorage resourceImageStorage;

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
    @DisplayName("목록 조회 시 imageKey가 있는 Resource는 imageUrl이 resolve되어 채워진다")
    void getResources_WithImageKey_ShouldResolveImageUrl() {
        // given
        Resource resource = resourceWithPolicy();
        resource.changeImageKey("resources/10/sample.jpg");
        Pageable pageable = PageRequest.of(0, 10);
        Page<Resource> page = new PageImpl<>(List.of(resource), pageable, 1);
        when(resourceRepository.findAll(pageable)).thenReturn(page);
        when(resourceImageStorage.generateReadUrl("resources/10/sample.jpg")).thenReturn("https://signed/sample.jpg");

        // when
        Page<ResourceResponse> response = resourceService.getResources(pageable);

        // then
        assertThat(response.getContent().get(0).imageUrl()).isEqualTo("https://signed/sample.jpg");
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
                new ReservationPolicySummaryResponse(15, 15, 60),
                "https://gymflow-resource-images.s3.ap-northeast-2.amazonaws.com/resources/10/sample.jpg");
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
    @DisplayName("imageKey가 없는 Resource를 조회하면 imageUrl은 null이며 Presigned URL을 생성하지 않는다")
    void getResourceDetail_WithoutImageKey_ShouldReturnNullImageUrl() {
        // given
        Resource resource = resourceWithPolicy();
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        ResourceResponse response = resourceService.getResourceDetail(RESOURCE_ID);

        // then
        assertThat(response.imageUrl()).isNull();
        verify(resourceImageStorage, never()).generateReadUrl(any());
    }

    @Test
    @DisplayName("imageKey가 있는 Resource를 조회하면 Presigned URL을 생성해 imageUrl로 반환한다")
    void getResourceDetail_WithImageKey_ShouldResolveImageUrl() {
        // given
        Resource resource = resourceWithPolicy();
        resource.changeImageKey("resources/10/sample.jpg");
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceImageStorage.generateReadUrl("resources/10/sample.jpg")).thenReturn("https://signed/sample.jpg");

        // when
        ResourceResponse response = resourceService.getResourceDetail(RESOURCE_ID);

        // then
        assertThat(response.imageUrl()).isEqualTo("https://signed/sample.jpg");
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

    private List<RankedResource> rankedBatch(long startId, int count, long startScore) {
        List<RankedResource> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new RankedResource(startId + i, startScore - i));
        }
        return batch;
    }

    @Test
    @DisplayName("TOP N: ACTIVE Resource만 있으면 정상적으로 rank/score를 반환한다")
    void getTopRankings_WithAllActiveResources_ShouldReturnRankedResponses() {
        // given
        List<RankedResource> candidates = List.of(
                new RankedResource(102L, 20L),
                new RankedResource(103L, 15L),
                new RankedResource(101L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(102L, 103L, 101L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(3);

        // then
        assertThat(result).extracting(ResourceRankingResponse::resourceId).containsExactly(102L, 103L, 101L);
        assertThat(result).extracting(ResourceRankingResponse::rank).containsExactly(1L, 2L, 3L);
        assertThat(result).extracting(ResourceRankingResponse::score).containsExactly(20L, 15L, 10L);
    }

    @Test
    @DisplayName("TOP N: 중간에 INACTIVE Resource가 있으면 제외하고 rank를 연속으로 재번호한다")
    void getTopRankings_WithInactiveInMiddle_ShouldExcludeAndRenumberRank() {
        // given
        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 30L),
                new RankedResource(102L, 20L),
                new RankedResource(103L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 102L, 103L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.INACTIVE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(3);

        // then
        assertThat(result).extracting(ResourceRankingResponse::resourceId).containsExactly(101L, 103L);
        assertThat(result).extracting(ResourceRankingResponse::rank).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("TOP N: MAINTENANCE Resource는 제외된다")
    void getTopRankings_WithMaintenanceResource_ShouldExclude() {
        // given
        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 30L),
                new RankedResource(102L, 20L),
                new RankedResource(103L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 102L, 103L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.MAINTENANCE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(3);

        // then
        assertThat(result).extracting(ResourceRankingResponse::resourceId).containsExactly(101L, 103L);
        assertThat(result).extracting(ResourceRankingResponse::rank).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("TOP N: 첫 batch로 limit을 채우지 못하면 다음 batch를 조회하고, limit을 채우면 즉시 종료한다")
    void getTopRankings_WhenFirstBatchInsufficient_ShouldScanNextBatchAndStopAtLimit() {
        // given: 첫 batch(offset 0, size 20)에는 ACTIVE가 3개뿐
        List<RankedResource> firstBatch = rankedBatch(1L, 20, 100L);
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(firstBatch);
        List<Long> firstBatchIds = firstBatch.stream().map(RankedResource::resourceId).toList();
        when(resourceRepository.findAllById(firstBatchIds)).thenReturn(List.of(
                resourceWithIdAndStatus(1L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(2L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(3L, ResourceStatus.ACTIVE)));

        // 두 번째 batch(offset 20, size 20)는 모두 ACTIVE: limit(5) 충족을 위해 21, 22만 소비되어야 한다
        List<RankedResource> secondBatch = rankedBatch(21L, 20, 50L);
        when(resourceRankingRedisRepository.findTopResources(20L, 20)).thenReturn(secondBatch);
        List<Long> secondBatchIds = secondBatch.stream().map(RankedResource::resourceId).toList();
        List<Resource> secondBatchResources = secondBatchIds.stream()
                .map(id -> resourceWithIdAndStatus(id, ResourceStatus.ACTIVE))
                .toList();
        when(resourceRepository.findAllById(secondBatchIds)).thenReturn(secondBatchResources);

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(5);

        // then
        assertThat(result).extracting(ResourceRankingResponse::resourceId)
                .containsExactly(1L, 2L, 3L, 21L, 22L);
        assertThat(result).extracting(ResourceRankingResponse::rank)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        verify(resourceRankingRedisRepository, never()).findTopResources(40L, 20);
    }

    @Test
    @DisplayName("TOP N: Redis 끝까지 scan해도 ACTIVE가 limit보다 적으면 존재하는 만큼만 반환한다")
    void getTopRankings_WhenActiveResourcesFewerThanLimit_ShouldReturnAvailableOnly() {
        // given
        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 10L),
                new RankedResource(102L, 8L),
                new RankedResource(103L, 5L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 102L, 103L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(10);

        // then
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("TOP N: MySQL에 존재하지 않는 stale Redis ID는 skip한다")
    void getTopRankings_WithStaleRedisId_ShouldSkipMissingResource() {
        // given
        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 10L),
                new RankedResource(999L, 8L),
                new RankedResource(103L, 5L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 999L, 103L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(10);

        // then
        assertThat(result).extracting(ResourceRankingResponse::resourceId).containsExactly(101L, 103L);
        assertThat(result).extracting(ResourceRankingResponse::rank).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("TOP N: findAllById 반환 순서가 달라도 Redis 원본 순서를 유지한다")
    void getTopRankings_WhenDbReturnsDifferentOrder_ShouldPreserveRedisOrder() {
        // given
        List<RankedResource> candidates = List.of(
                new RankedResource(13L, 30L),
                new RankedResource(7L, 20L),
                new RankedResource(21L, 10L),
                new RankedResource(4L, 5L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(13L, 7L, 21L, 4L))).thenReturn(List.of(
                resourceWithIdAndStatus(4L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(7L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(13L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(21L, ResourceStatus.ACTIVE)));

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(4);

        // then
        assertThat(result).extracting(ResourceRankingResponse::resourceId)
                .containsExactly(13L, 7L, 21L, 4L);
    }

    @Test
    @DisplayName("TOP N: score가 동점이면 Repository가 반환한 순서를 그대로 유지한다")
    void getTopRankings_WithTiedScores_ShouldPreserveRepositoryOrder() {
        // given
        List<RankedResource> candidates = List.of(
                new RankedResource(103L, 10L),
                new RankedResource(101L, 10L),
                new RankedResource(102L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(103L, 101L, 102L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE)));

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(3);

        // then: Java에서 별도 재정렬을 하지 않으므로 Repository가 반환한 순서를 그대로 따른다
        assertThat(result).extracting(ResourceRankingResponse::resourceId)
                .containsExactly(103L, 101L, 102L);
    }

    @Test
    @DisplayName("TOP N: Ranking이 비어있으면 빈 목록을 반환하고 MySQL을 조회하지 않는다")
    void getTopRankings_WithEmptyRanking_ShouldReturnEmptyListWithoutQueryingMySql() {
        // given
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(List.of());

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(10);

        // then
        assertThat(result).isEmpty();
        verify(resourceRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("TOP N: 첫 batch에서 Redis 장애가 발생하면 빈 목록을 반환한다")
    void getTopRankings_WithFirstBatchRedisFailure_ShouldReturnEmptyList() {
        // given
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(resourceRankingRedisRepository).findTopResources(0L, 20);

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(10);

        // then
        assertThat(result).isEmpty();
        verify(resourceRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("TOP N: 중간 batch에서 Redis 장애가 발생하면 이전 batch에서 모은 부분 결과를 폐기하고 빈 목록을 반환한다")
    void getTopRankings_WithMidBatchRedisFailure_ShouldDiscardPartialResultAndReturnEmptyList() {
        // given: 첫 batch에서 ACTIVE 5개를 모았지만 limit(10)에 미달하여 다음 batch를 조회해야 함
        List<RankedResource> firstBatch = rankedBatch(1L, 20, 100L);
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(firstBatch);
        List<Long> firstBatchIds = firstBatch.stream().map(RankedResource::resourceId).toList();
        List<Resource> firstBatchResources = List.of(
                resourceWithIdAndStatus(1L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(2L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(3L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(4L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(5L, ResourceStatus.ACTIVE));
        when(resourceRepository.findAllById(firstBatchIds)).thenReturn(firstBatchResources);
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(resourceRankingRedisRepository).findTopResources(20L, 20);

        // when
        List<ResourceRankingResponse> result = resourceService.getTopRankings(10);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("TOP N: MySQL 조회 중 예외가 발생하면 삼키지 않고 그대로 전파한다")
    void getTopRankings_WithDatabaseFailure_ShouldPropagateException() {
        // given
        List<RankedResource> candidates = List.of(new RankedResource(101L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L))).thenThrow(new RuntimeException("DB 오류"));

        // when & then
        assertThatThrownBy(() -> resourceService.getTopRankings(10))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 오류");
    }

    @Test
    @DisplayName("특정 Resource: ACTIVE이고 Ranking에 등록되어 있으면 ACTIVE 기준 rank와 score를 반환한다")
    void getResourceRanking_WithActiveAndRanked_ShouldReturnRankAndScore() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.of(15L));

        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 30L),
                new RankedResource(103L, 15L),
                new RankedResource(102L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 103L, 102L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.ACTIVE),
                target));

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then
        assertThat(response.rank()).isEqualTo(2L);
        assertThat(response.score()).isEqualTo(15L);
        assertThat(response.resourceId()).isEqualTo(103L);
    }

    @Test
    @DisplayName("특정 Resource: ACTIVE이지만 Ranking에 없으면 rank=null, score=0을 반환하고 scan을 하지 않는다")
    void getResourceRanking_WithActiveAndUnranked_ShouldReturnNullRankAndZeroScore() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.empty());

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then
        assertThat(response.rank()).isNull();
        assertThat(response.score()).isEqualTo(0L);
        verify(resourceRankingRedisRepository, never()).findTopResources(anyLong(), anyInt());
    }

    @Test
    @DisplayName("특정 Resource: 앞선 순위에 INACTIVE Resource가 있으면 rank를 보정한다")
    void getResourceRanking_WithInactiveResourceAhead_ShouldAdjustRank() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.of(10L));

        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 30L),
                new RankedResource(102L, 20L),
                new RankedResource(103L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 102L, 103L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.INACTIVE),
                target));

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then: 101(1위) -> 102 제외 -> 103(2위)
        assertThat(response.rank()).isEqualTo(2L);
    }

    @Test
    @DisplayName("특정 Resource: 앞선 순위에 MAINTENANCE Resource가 있으면 rank를 보정한다")
    void getResourceRanking_WithMaintenanceResourceAhead_ShouldAdjustRank() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.of(10L));

        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 30L),
                new RankedResource(102L, 20L),
                new RankedResource(103L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 102L, 103L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                resourceWithIdAndStatus(102L, ResourceStatus.MAINTENANCE),
                target));

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then
        assertThat(response.rank()).isEqualTo(2L);
    }

    @Test
    @DisplayName("특정 Resource: 두 번째 batch에 존재하면 정확한 rank를 계산한다")
    void getResourceRanking_WithTargetInSecondBatch_ShouldReturnAccurateRank() {
        // given
        Resource target = resourceWithIdAndStatus(999L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(999L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(999L)).thenReturn(Optional.of(5L));

        List<RankedResource> firstBatch = rankedBatch(1L, 20, 100L);
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(firstBatch);
        List<Long> firstBatchIds = firstBatch.stream().map(RankedResource::resourceId).toList();
        List<Resource> firstBatchResources = firstBatchIds.stream()
                .map(id -> resourceWithIdAndStatus(id, ResourceStatus.ACTIVE))
                .toList();
        when(resourceRepository.findAllById(firstBatchIds)).thenReturn(firstBatchResources);

        List<RankedResource> secondBatch = List.of(
                new RankedResource(21L, 8L),
                new RankedResource(999L, 5L));
        when(resourceRankingRedisRepository.findTopResources(20L, 20)).thenReturn(secondBatch);
        when(resourceRepository.findAllById(List.of(21L, 999L))).thenReturn(List.of(
                resourceWithIdAndStatus(21L, ResourceStatus.ACTIVE),
                target));

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(999L);

        // then: 첫 batch 20개(1위~20위) + 두 번째 batch의 21(21위), 999(22위)
        assertThat(response.rank()).isEqualTo(22L);
    }

    @Test
    @DisplayName("특정 Resource: 앞선 순위에 stale Redis ID가 있어도 rank가 정상적으로 계산된다")
    void getResourceRanking_WithStaleIdAhead_ShouldReturnAccurateRank() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.of(10L));

        List<RankedResource> candidates = List.of(
                new RankedResource(101L, 30L),
                new RankedResource(999L, 20L),
                new RankedResource(103L, 10L));
        when(resourceRankingRedisRepository.findTopResources(0L, 20)).thenReturn(candidates);
        when(resourceRepository.findAllById(List.of(101L, 999L, 103L))).thenReturn(List.of(
                resourceWithIdAndStatus(101L, ResourceStatus.ACTIVE),
                target));

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then: stale ID는 rank에 반영되지 않으므로 101(1위) -> 103(2위)
        assertThat(response.rank()).isEqualTo(2L);
    }

    @Test
    @DisplayName("특정 Resource: INACTIVE이고 score가 존재하면 rank=null이며 score는 유지된다")
    void getResourceRanking_WithInactiveAndScore_ShouldReturnNullRankAndKeptScore() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.INACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.of(128L));

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then
        assertThat(response.rank()).isNull();
        assertThat(response.score()).isEqualTo(128L);
        verify(resourceRankingRedisRepository, never()).findTopResources(anyLong(), anyInt());
    }

    @Test
    @DisplayName("특정 Resource: MAINTENANCE이고 score가 존재하면 rank=null이며 score는 유지된다")
    void getResourceRanking_WithMaintenanceAndScore_ShouldReturnNullRankAndKeptScore() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.MAINTENANCE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.of(128L));

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then
        assertThat(response.rank()).isNull();
        assertThat(response.score()).isEqualTo(128L);
        verify(resourceRankingRedisRepository, never()).findTopResources(anyLong(), anyInt());
    }

    @Test
    @DisplayName("특정 Resource: 존재하지 않으면 RESOURCE_NOT_FOUND 예외를 던진다")
    void getResourceRanking_WithNonExistentResource_ShouldThrowException() {
        // given
        when(resourceRepository.findById(103L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> resourceService.getResourceRanking(103L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        verify(resourceRankingRedisRepository, never()).findScore(any());
    }

    @Test
    @DisplayName("특정 Resource: findScore에서 Redis 장애가 발생하면 rank=null, score=0을 반환한다")
    void getResourceRanking_WithFindScoreRedisFailure_ShouldReturnNullRankAndZeroScore() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(resourceRankingRedisRepository).findScore(103L);

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then
        assertThat(response.rank()).isNull();
        assertThat(response.score()).isEqualTo(0L);
    }

    @Test
    @DisplayName("특정 Resource: score 조회 후 rank scan 중 Redis 장애가 발생하면 최종적으로 rank=null, score=0을 반환한다")
    void getResourceRanking_WithRankScanRedisFailureAfterScoreFound_ShouldReturnNullRankAndZeroScore() {
        // given
        Resource target = resourceWithIdAndStatus(103L, ResourceStatus.ACTIVE);
        when(resourceRepository.findById(103L)).thenReturn(Optional.of(target));
        when(resourceRankingRedisRepository.findScore(103L)).thenReturn(Optional.of(100L));
        doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(resourceRankingRedisRepository).findTopResources(0L, 20);

        // when
        ResourceRankingResponse response = resourceService.getResourceRanking(103L);

        // then: score를 이미 찾았더라도 rank scan이 실패하면 Ranking 전체를 unavailable로 취급한다
        assertThat(response.rank()).isNull();
        assertThat(response.score()).isEqualTo(0L);
    }

    @Test
    @DisplayName("특정 Resource: MySQL findById에서 예외가 발생하면 삼키지 않고 그대로 전파한다")
    void getResourceRanking_WithDatabaseFailureOnFindById_ShouldPropagateException() {
        // given
        when(resourceRepository.findById(103L)).thenThrow(new RuntimeException("DB 오류"));

        // when & then
        assertThatThrownBy(() -> resourceService.getResourceRanking(103L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 오류");
    }
}
