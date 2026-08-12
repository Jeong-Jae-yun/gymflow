package com.gymflow.domain.resource.service;

import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.resource.dto.response.ResourceResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    private static final Long RESOURCE_ID = 10L;

    @Mock
    private ResourceRepository resourceRepository;

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
    @DisplayName("본인의 Resource 상세를 정상적으로 조회한다")
    void getResourceDetail_WithExistingResource_ShouldReturnResponse() {
        // given
        Resource resource = resourceWithPolicy();
        when(resourceRepository.findWithReservationPolicyById(RESOURCE_ID)).thenReturn(Optional.of(resource));

        // when
        ResourceResponse response = resourceService.getResourceDetail(RESOURCE_ID);

        // then
        assertThat(response.id()).isEqualTo(RESOURCE_ID);
        assertThat(response.name()).isEqualTo("Chest Press A-1");
        assertThat(response.capacity()).isEqualTo(1);
        assertThat(response.reservationPolicy().slotDuration()).isEqualTo(15);
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
}
