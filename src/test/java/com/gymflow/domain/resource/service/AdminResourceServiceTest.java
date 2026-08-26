package com.gymflow.domain.resource.service;

import com.gymflow.domain.reservation.domain.enumtype.ReservationStatus;
import com.gymflow.domain.reservation.domain.repository.ReservationRepository;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceAvailabilityLockRepository;
import com.gymflow.domain.resource.domain.redis.ResourceCacheRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.request.AdminResourceCreateRequest;
import com.gymflow.domain.resource.dto.request.AdminResourceStatusUpdateRequest;
import com.gymflow.domain.resource.dto.request.AdminResourceUpdateRequest;
import com.gymflow.domain.resource.dto.response.AdminResourceResponse;
import com.gymflow.domain.waitingqueue.service.PromotionService;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.common.transaction.TransactionAwareLockReleaser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminResourceServiceTest {

    private static final Long RESOURCE_ID = 10L;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private PromotionService promotionService;

    @Mock
    private ResourceAvailabilityLockRepository resourceAvailabilityLockRepository;

    @Mock
    private ResourceCacheRepository resourceCacheRepository;

    @Mock
    private TransactionAwareLockReleaser lockReleaser;

    @InjectMocks
    private AdminResourceService adminResourceService;

    private Resource resourceWithId(Long id, ResourceStatus status) {
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .description("3F Weight Zone")
                .build();
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(60)
                .build();
        ReflectionTestUtils.setField(resource, "id", id);
        ReflectionTestUtils.setField(resource, "status", status);
        return resource;
    }

    private AdminResourceCreateRequest createRequest() {
        return new AdminResourceCreateRequest(
                "Chest Press A-1", ResourceType.MACHINE, 1, "3F Weight Zone", 15, 15, 60);
    }

    private AdminResourceUpdateRequest updateRequest() {
        return new AdminResourceUpdateRequest("Chest Press A-2", 2, "4F Weight Zone", 30, 30, 90);
    }

    // ===== 생성 =====

    @Test
    @DisplayName("유효한 요청으로 Resource와 ReservationPolicy를 함께 생성하고 ACTIVE 상태로 저장한다")
    void createResource_WithValidRequest_ShouldCreateResourceAndPolicyAsActive() {
        // given
        when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        AdminResourceResponse response = adminResourceService.createResource(createRequest());

        // then
        ArgumentCaptor<Resource> captor = ArgumentCaptor.forClass(Resource.class);
        verify(resourceRepository).save(captor.capture());
        Resource savedResource = captor.getValue();

        assertThat(savedResource.getName()).isEqualTo("Chest Press A-1");
        assertThat(savedResource.getType()).isEqualTo(ResourceType.MACHINE);
        assertThat(savedResource.getStatus()).isEqualTo(ResourceStatus.ACTIVE);
        assertThat(savedResource.getCapacity()).isEqualTo(1);
        assertThat(savedResource.getReservationPolicy()).isNotNull();
        assertThat(savedResource.getReservationPolicy().getSlotDuration()).isEqualTo(15);
        assertThat(savedResource.getReservationPolicy().getMinDuration()).isEqualTo(15);
        assertThat(savedResource.getReservationPolicy().getMaxDuration()).isEqualTo(60);
        assertThat(savedResource.getReservationPolicy().getResource()).isSameAs(savedResource);

        assertThat(response.type()).isEqualTo(ResourceType.MACHINE);
        assertThat(response.status()).isEqualTo(ResourceStatus.ACTIVE);
        assertThat(response.slotDuration()).isEqualTo(15);
        assertThat(response.maxDuration()).isEqualTo(60);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    @DisplayName("모든 ResourceType으로 Resource를 생성할 수 있다")
    void createResource_WithEachResourceType_ShouldRetainType(ResourceType type) {
        // given
        when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AdminResourceCreateRequest request = new AdminResourceCreateRequest(
                type.name() + " Resource", type, 1, null, 15, 15, 60);

        // when
        AdminResourceResponse response = adminResourceService.createResource(request);

        // then
        assertThat(response.type()).isEqualTo(type);
    }

    @Test
    @DisplayName("maxDuration이 minDuration보다 작으면 생성에 실패하고 저장하지 않는다")
    void createResource_WithMaxDurationLessThanMinDuration_ShouldThrowExceptionWithoutSaving() {
        // given
        AdminResourceCreateRequest request = new AdminResourceCreateRequest(
                "Chest Press A-1", ResourceType.MACHINE, 1, null, 15, 60, 30);

        // when & then
        assertThatThrownBy(() -> adminResourceService.createResource(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_POLICY);
        verify(resourceRepository, never()).save(any());
    }

    @Test
    @DisplayName("minDuration이 slotDuration의 배수가 아니면 생성에 실패한다")
    void createResource_WithMinDurationNotMultipleOfSlot_ShouldThrowException() {
        // given
        AdminResourceCreateRequest request = new AdminResourceCreateRequest(
                "Chest Press A-1", ResourceType.MACHINE, 1, null, 15, 20, 60);

        // when & then
        assertThatThrownBy(() -> adminResourceService.createResource(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_POLICY);
        verify(resourceRepository, never()).save(any());
    }

    @Test
    @DisplayName("maxDuration이 slotDuration의 배수가 아니면 생성에 실패한다")
    void createResource_WithMaxDurationNotMultipleOfSlot_ShouldThrowException() {
        // given
        AdminResourceCreateRequest request = new AdminResourceCreateRequest(
                "Chest Press A-1", ResourceType.MACHINE, 1, null, 15, 15, 50);

        // when & then
        assertThatThrownBy(() -> adminResourceService.createResource(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_POLICY);
        verify(resourceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Resource 생성 시에는 Cache Evict를 호출하지 않는다")
    void createResource_ShouldNotEvictCache() {
        // given
        when(resourceRepository.save(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        adminResourceService.createResource(createRequest());

        // then
        verify(resourceCacheRepository, never()).evict(any());
    }

    // ===== 수정 =====

    @Test
    @DisplayName("유효한 요청으로 name/capacity/description/Policy를 수정하고 Cache를 evict한다")
    void updateResource_WithValidRequest_ShouldUpdateFieldsAndEvictCache() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        AdminResourceResponse response = adminResourceService.updateResource(RESOURCE_ID, updateRequest());

        // then
        assertThat(response.name()).isEqualTo("Chest Press A-2");
        assertThat(response.capacity()).isEqualTo(2);
        assertThat(response.description()).isEqualTo("4F Weight Zone");
        assertThat(response.slotDuration()).isEqualTo(30);
        assertThat(response.minDuration()).isEqualTo(30);
        assertThat(response.maxDuration()).isEqualTo(90);
        assertThat(response.type()).isEqualTo(ResourceType.MACHINE);
        assertThat(response.status()).isEqualTo(ResourceStatus.ACTIVE);
        verify(resourceCacheRepository).evict(RESOURCE_ID);
    }

    @Test
    @DisplayName("수정해도 type과 status는 변경되지 않는다")
    void updateResource_ShouldKeepTypeAndStatusUnchanged() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.MAINTENANCE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        AdminResourceResponse response = adminResourceService.updateResource(RESOURCE_ID, updateRequest());

        // then
        assertThat(response.type()).isEqualTo(ResourceType.MACHINE);
        assertThat(response.status()).isEqualTo(ResourceStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("존재하지 않는 Resource를 수정하면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void updateResource_WithNonExistentResource_ShouldThrowResourceNotFound() {
        // given
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminResourceService.updateResource(RESOURCE_ID, updateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        verify(resourceCacheRepository, never()).evict(any());
    }

    @Test
    @DisplayName("ReservationPolicy가 없는 Resource를 수정하면 RESERVATION_POLICY_NOT_FOUND 예외가 발생한다")
    void updateResource_WithoutReservationPolicy_ShouldThrowReservationPolicyNotFound() {
        // given
        Resource resource = Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", RESOURCE_ID);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when & then
        assertThatThrownBy(() -> adminResourceService.updateResource(RESOURCE_ID, updateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_POLICY_NOT_FOUND);
        verify(resourceCacheRepository, never()).evict(any());
    }

    @Test
    @DisplayName("잘못된 Policy 조합으로 수정하면 예외가 발생하고 기존 값을 유지하며 Cache를 evict하지 않는다")
    void updateResource_WithInvalidPolicyCombination_ShouldThrowExceptionAndKeepOriginalValues() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        AdminResourceUpdateRequest invalidRequest =
                new AdminResourceUpdateRequest("Chest Press A-2", 2, null, 15, 60, 30);

        // when & then
        assertThatThrownBy(() -> adminResourceService.updateResource(RESOURCE_ID, invalidRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_RESERVATION_POLICY);

        assertThat(resource.getName()).isEqualTo("Chest Press A-1");
        assertThat(resource.getReservationPolicy().getMinDuration()).isEqualTo(15);
        verify(resourceCacheRepository, never()).evict(any());
    }

    @Test
    @DisplayName("Cache Evict가 실패해도 MySQL 변경 사항은 유지되고 API는 성공 응답을 반환한다 (Fail-Open)")
    void updateResource_WhenCacheEvictFails_ShouldStillReturnUpdatedResponse() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        doThrow(new RedisConnectionFailureException("연결 실패")).when(resourceCacheRepository).evict(RESOURCE_ID);

        // when
        AdminResourceResponse response = adminResourceService.updateResource(RESOURCE_ID, updateRequest());

        // then
        assertThat(response.name()).isEqualTo("Chest Press A-2");
        assertThat(resource.getName()).isEqualTo("Chest Press A-2");
    }

    // ===== 상태 변경 =====

    @Test
    @DisplayName("ACTIVE에서 MAINTENANCE로 충돌이 없으면 상태 변경에 성공하고 Cache를 evict한다")
    void changeStatus_FromActiveToMaintenance_WithoutConflict_ShouldSucceedAndEvictCache() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.of("lock-token"));
        when(reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(eq(RESOURCE_ID), anyCollection(), any()))
                .thenReturn(false);
        when(promotionService.hasActiveOfferedPromotion(eq(RESOURCE_ID), any())).thenReturn(false);

        // when
        AdminResourceResponse response = adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE));

        // then
        assertThat(response.status()).isEqualTo(ResourceStatus.MAINTENANCE);
        verify(resourceCacheRepository).evict(RESOURCE_ID);
        verify(resourceAvailabilityLockRepository).unlock(RESOURCE_ID, "lock-token");
    }

    @Test
    @DisplayName("ACTIVE에서 INACTIVE로 충돌이 없으면 상태 변경에 성공한다")
    void changeStatus_FromActiveToInactive_WithoutConflict_ShouldSucceed() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.of("lock-token"));
        when(reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(eq(RESOURCE_ID), anyCollection(), any()))
                .thenReturn(false);
        when(promotionService.hasActiveOfferedPromotion(eq(RESOURCE_ID), any())).thenReturn(false);

        // when
        AdminResourceResponse response = adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.INACTIVE));

        // then
        assertThat(response.status()).isEqualTo(ResourceStatus.INACTIVE);
    }

    @Test
    @DisplayName("MAINTENANCE에서 ACTIVE로는 충돌 검사 없이 상태 변경에 성공한다")
    void changeStatus_FromMaintenanceToActive_ShouldSucceedWithoutConflictCheck() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.MAINTENANCE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        AdminResourceResponse response = adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.ACTIVE));

        // then
        assertThat(response.status()).isEqualTo(ResourceStatus.ACTIVE);
        verify(reservationRepository, never()).existsByResourceIdAndStatusInAndEndAtAfter(any(), any(), any());
        verify(promotionService, never()).hasActiveOfferedPromotion(any(), any());
        verify(resourceAvailabilityLockRepository, never()).tryLock(any());
    }

    @Test
    @DisplayName("INACTIVE에서 ACTIVE로는 충돌 검사 없이 상태 변경에 성공한다")
    void changeStatus_FromInactiveToActive_ShouldSucceedWithoutConflictCheck() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.INACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        AdminResourceResponse response = adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.ACTIVE));

        // then
        assertThat(response.status()).isEqualTo(ResourceStatus.ACTIVE);
        verify(reservationRepository, never()).existsByResourceIdAndStatusInAndEndAtAfter(any(), any(), any());
        verify(resourceAvailabilityLockRepository, never()).tryLock(any());
    }

    @ParameterizedTest
    @EnumSource(ResourceStatus.class)
    @DisplayName("동일한 상태로의 변경 요청은 충돌 검사 없이 idempotent하게 성공한다")
    void changeStatus_WithSameStatus_ShouldSucceedIdempotentlyWithoutConflictCheck(ResourceStatus status) {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, status);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        AdminResourceResponse response = adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(status));

        // then
        assertThat(response.status()).isEqualTo(status);
        verify(reservationRepository, never()).existsByResourceIdAndStatusInAndEndAtAfter(any(), any(), any());
        verify(promotionService, never()).hasActiveOfferedPromotion(any(), any());
        verify(resourceAvailabilityLockRepository, never()).tryLock(any());
    }

    @Test
    @DisplayName("존재하지 않는 Resource의 상태를 변경하면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void changeStatus_WithNonExistentResource_ShouldThrowResourceNotFound() {
        // given
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("미래의 CONFIRMED 예약이 있으면 MAINTENANCE 전환이 거부된다")
    void changeStatus_ToMaintenance_WithFutureConfirmedReservation_ShouldThrowConflict() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.of("lock-token"));
        when(reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any())).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_STATUS_CHANGE_NOT_ALLOWED);
        assertThat(resource.getStatus()).isEqualTo(ResourceStatus.ACTIVE);
        verify(resourceCacheRepository, never()).evict(any());
        verify(resourceAvailabilityLockRepository).unlock(RESOURCE_ID, "lock-token");
    }

    @Test
    @DisplayName("진행 중인 CHECKED_IN 예약이 있으면 INACTIVE 전환이 거부된다")
    void changeStatus_ToInactive_WithCheckedInReservation_ShouldThrowConflict() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.of("lock-token"));
        when(reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any())).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.INACTIVE)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_STATUS_CHANGE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("COMPLETED/CANCELLED/NO_SHOW 예약만 존재하면 MAINTENANCE 전환이 가능하다")
    void changeStatus_ToMaintenance_WithOnlyNonOccupyingReservations_ShouldSucceed() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.of("lock-token"));
        when(reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any())).thenReturn(false);
        when(promotionService.hasActiveOfferedPromotion(eq(RESOURCE_ID), any())).thenReturn(false);

        // when
        AdminResourceResponse response = adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE));

        // then
        assertThat(response.status()).isEqualTo(ResourceStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("활성 OFFERED Promotion이 있으면 MAINTENANCE 전환이 거부된다")
    void changeStatus_ToMaintenance_WithActiveOfferedPromotion_ShouldThrowConflict() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.of("lock-token"));
        when(reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(
                eq(RESOURCE_ID), eq(ReservationStatus.OCCUPYING_STATUSES), any())).thenReturn(false);
        when(promotionService.hasActiveOfferedPromotion(eq(RESOURCE_ID), any())).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_STATUS_CHANGE_NOT_ALLOWED);
        assertThat(resource.getStatus()).isEqualTo(ResourceStatus.ACTIVE);
        verify(resourceCacheRepository, never()).evict(any());
    }

    @Test
    @DisplayName("Cache Evict가 실패해도 상태 변경은 유지되고 API는 성공 응답을 반환한다 (Fail-Open)")
    void changeStatus_WhenCacheEvictFails_ShouldStillSucceed() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.of("lock-token"));
        when(reservationRepository.existsByResourceIdAndStatusInAndEndAtAfter(eq(RESOURCE_ID), anyCollection(), any()))
                .thenReturn(false);
        when(promotionService.hasActiveOfferedPromotion(eq(RESOURCE_ID), any())).thenReturn(false);
        doThrow(new RedisConnectionFailureException("연결 실패")).when(resourceCacheRepository).evict(RESOURCE_ID);

        // when
        AdminResourceResponse response = adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE));

        // then
        assertThat(response.status()).isEqualTo(ResourceStatus.MAINTENANCE);
        assertThat(resource.getStatus()).isEqualTo(ResourceStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("ResourceAvailabilityLock 획득에 실패하면 상태 변경을 진행하지 않는다 (Fail-Closed)")
    void changeStatus_WhenAvailabilityLockAcquisitionFails_ShouldNotChangeStatus() {
        // given
        Resource resource = resourceWithId(RESOURCE_ID, ResourceStatus.ACTIVE);
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));
        when(resourceAvailabilityLockRepository.tryLock(RESOURCE_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminResourceService.changeStatus(
                RESOURCE_ID, new AdminResourceStatusUpdateRequest(ResourceStatus.MAINTENANCE)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESERVATION_IN_PROGRESS);
        assertThat(resource.getStatus()).isEqualTo(ResourceStatus.ACTIVE);
        verify(reservationRepository, never()).existsByResourceIdAndStatusInAndEndAtAfter(any(), any(), any());
        verify(resourceCacheRepository, never()).evict(any());
        verify(resourceAvailabilityLockRepository, never()).unlock(any(), any());
    }
}
