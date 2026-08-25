package com.gymflow.domain.resource.domain.entity;

import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationPolicyTest {

    private Resource createResource() {
        return Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
    }

    @Test
    @DisplayName("ReservationPolicy는 MVP 기본 정책(15/15/15) 값으로 정상 생성된다")
    void createPolicy_WithMvpDefaultValues_ShouldSucceed() {
        // given
        Resource resource = createResource();

        // when
        ReservationPolicy policy = ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(15)
                .build();

        // then
        assertThat(policy.getSlotDuration()).isEqualTo(15);
        assertThat(policy.getMinDuration()).isEqualTo(15);
        assertThat(policy.getMaxDuration()).isEqualTo(15);
    }

    @Test
    @DisplayName("Resource마다 다른 예약 시간 정책을 지정할 수 있다")
    void createPolicy_WithCustomDurations_ShouldRetainGivenValues() {
        // given
        Resource resource = createResource();

        // when
        ReservationPolicy policy = ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(30)
                .minDuration(30)
                .maxDuration(120)
                .build();

        // then
        assertThat(policy.getSlotDuration()).isEqualTo(30);
        assertThat(policy.getMinDuration()).isEqualTo(30);
        assertThat(policy.getMaxDuration()).isEqualTo(120);
    }

    @Test
    @DisplayName("ReservationPolicy를 생성하면 Resource와 양방향으로 연결된다")
    void createPolicy_ShouldLinkBothSidesOfRelationship() {
        // given
        Resource resource = createResource();

        // when
        ReservationPolicy policy = ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(15)
                .build();

        // then
        assertThat(policy.getResource()).isSameAs(resource);
        assertThat(resource.getReservationPolicy()).isSameAs(policy);
    }

    @Test
    @DisplayName("Resource 없이 ReservationPolicy를 생성하면 예외가 발생한다")
    void createPolicy_WithoutResource_ShouldThrowException() {
        assertThatThrownBy(() -> ReservationPolicy.builder()
                .resource(null)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(15)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource");
    }

    @Test
    @DisplayName("minDuration이 maxDuration보다 크면 예외가 발생한다")
    void createPolicy_WithMinDurationGreaterThanMaxDuration_ShouldThrowException() {
        assertThatThrownBy(() -> ReservationPolicy.builder()
                .resource(createResource())
                .slotDuration(15)
                .minDuration(60)
                .maxDuration(30)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minDuration은 maxDuration보다 클 수 없습니다.");
    }

    @ParameterizedTest
    @CsvSource({
            "0, 15, 15",
            "-15, 15, 15",
            "15, 0, 15",
            "15, -15, 15",
            "15, 15, 0",
            "15, 15, -15"
    })
    @DisplayName("slotDuration/minDuration/maxDuration이 0 이하이면 예외가 발생한다")
    void createPolicy_WithNonPositiveDuration_ShouldThrowException(int slotDuration, int minDuration, int maxDuration) {
        assertThatThrownBy(() -> ReservationPolicy.builder()
                .resource(createResource())
                .slotDuration(slotDuration)
                .minDuration(minDuration)
                .maxDuration(maxDuration)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1분 이상");
    }

    @Test
    @DisplayName("minDuration/maxDuration이 slotDuration의 배수가 아니어도 정상 생성된다")
    void createPolicy_WithDurationNotMultipleOfSlot_ShouldSucceed() {
        // given
        Resource resource = createResource();

        // when
        ReservationPolicy policy = ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(20)
                .maxDuration(50)
                .build();

        // then
        assertThat(policy.getSlotDuration()).isEqualTo(15);
        assertThat(policy.getMinDuration()).isEqualTo(20);
        assertThat(policy.getMaxDuration()).isEqualTo(50);
    }
}
