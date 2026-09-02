package com.gymflow.domain.waitingqueue.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private PromotionMetrics promotionMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        promotionMetrics = new PromotionMetrics(meterRegistry);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordAccepted()는 즉시 gymflow_promotion_accepted_total을 증가시킨다")
    void recordAccepted_WithoutActiveTransaction_ShouldIncrementImmediately() {
        promotionMetrics.recordAccepted();

        assertThat(meterRegistry.get("gymflow_promotion_accepted_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordRejected()는 즉시 gymflow_promotion_rejected_total을 증가시킨다")
    void recordRejected_WithoutActiveTransaction_ShouldIncrementImmediately() {
        promotionMetrics.recordRejected();

        assertThat(meterRegistry.get("gymflow_promotion_rejected_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordExpired()는 즉시 gymflow_promotion_expired_total을 증가시킨다")
    void recordExpired_WithoutActiveTransaction_ShouldIncrementImmediately() {
        promotionMetrics.recordExpired();

        assertThat(meterRegistry.get("gymflow_promotion_expired_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("accepted/rejected/expired counter는 서로 독립적으로 집계된다")
    void counters_ShouldBeIndependent() {
        promotionMetrics.recordAccepted();
        promotionMetrics.recordAccepted();
        promotionMetrics.recordRejected();
        promotionMetrics.recordExpired();
        promotionMetrics.recordExpired();
        promotionMetrics.recordExpired();

        assertThat(meterRegistry.get("gymflow_promotion_accepted_total").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("gymflow_promotion_rejected_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_promotion_expired_total").counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("모든 metric에는 promotionId/userId/resourceId 등 고카디널리티 tag가 없다")
    void metrics_ShouldHaveNoHighCardinalityTags() {
        promotionMetrics.recordAccepted();
        promotionMetrics.recordRejected();
        promotionMetrics.recordExpired();

        assertThat(meterRegistry.get("gymflow_promotion_accepted_total").counter().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_promotion_rejected_total").counter().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_promotion_expired_total").counter().getId().getTags()).isEmpty();
    }
}
