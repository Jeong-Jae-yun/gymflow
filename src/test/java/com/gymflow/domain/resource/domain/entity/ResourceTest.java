package com.gymflow.domain.resource.domain.entity;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceTest {

    @Test
    @DisplayName("Resource는 필수 값으로 정상 생성되며 기본 status는 ACTIVE이다")
    void createResource_WithRequiredFields_ShouldHaveDefaultActiveStatus() {
        // given
        String name = "Chest Press A-1";
        ResourceType type = ResourceType.MACHINE;
        Integer capacity = 1;
        String description = "3F Weight Zone";

        // when
        Resource resource = Resource.builder()
                .name(name)
                .type(type)
                .capacity(capacity)
                .description(description)
                .build();

        // then
        assertThat(resource.getName()).isEqualTo(name);
        assertThat(resource.getType()).isEqualTo(type);
        assertThat(resource.getCapacity()).isEqualTo(capacity);
        assertThat(resource.getDescription()).isEqualTo(description);
        assertThat(resource.getStatus()).isEqualTo(ResourceStatus.ACTIVE);
        assertThat(resource.getReservationPolicy()).isNull();
    }

    @Test
    @DisplayName("description은 nullable이므로 없이도 생성할 수 있다")
    void createResource_WithoutDescription_ShouldSucceed() {
        // when
        Resource resource = Resource.builder()
                .name("Locker A-12")
                .type(ResourceType.LOCKER)
                .capacity(1)
                .build();

        // then
        assertThat(resource.getDescription()).isNull();
        assertThat(resource.getStatus()).isEqualTo(ResourceStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    @DisplayName("모든 ResourceType으로 Resource를 생성하면 전달한 type이 그대로 유지된다")
    void createResource_WithEachResourceType_ShouldRetainType(ResourceType type) {
        // when
        Resource resource = Resource.builder()
                .name(type.name() + " Resource")
                .type(type)
                .capacity(1)
                .build();

        // then
        assertThat(resource.getType()).isEqualTo(type);
        assertThat(resource.getType().name()).isEqualTo(type.name());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -15})
    @DisplayName("capacity가 1 미만이면 Resource 생성에 실패한다")
    void createResource_WithCapacityBelowOne_ShouldThrowException(int capacity) {
        assertThatThrownBy(() -> Resource.builder()
                .name("Invalid Resource")
                .type(ResourceType.MACHINE)
                .capacity(capacity)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    @DisplayName("capacity가 null이면 Resource 생성에 실패한다")
    void createResource_WithNullCapacity_ShouldThrowException() {
        assertThatThrownBy(() -> Resource.builder()
                .name("Invalid Resource")
                .type(ResourceType.MACHINE)
                .capacity(null)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }
}
