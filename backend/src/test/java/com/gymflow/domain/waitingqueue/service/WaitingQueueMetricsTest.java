package com.gymflow.domain.waitingqueue.service;

import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import com.gymflow.domain.waitingqueue.domain.repository.WaitingQueueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitingQueueMetricsTest {

    @Mock
    private WaitingQueueRepository waitingQueueRepository;

    private SimpleMeterRegistry meterRegistry;
    private WaitingQueueMetrics waitingQueueMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        waitingQueueMetrics = new WaitingQueueMetrics(meterRegistry, waitingQueueRepository);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordJoined()는 즉시 gymflow_waiting_queue_joined_total을 증가시킨다")
    void recordJoined_WithoutActiveTransaction_ShouldIncrementImmediately() {
        waitingQueueMetrics.recordJoined();

        assertThat(meterRegistry.get("gymflow_waiting_queue_joined_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordCancelled()는 즉시 gymflow_waiting_queue_cancelled_total을 증가시킨다")
    void recordCancelled_WithoutActiveTransaction_ShouldIncrementImmediately() {
        waitingQueueMetrics.recordCancelled();

        assertThat(meterRegistry.get("gymflow_waiting_queue_cancelled_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("활성 transaction이 없으면 recordPromoted()는 즉시 gymflow_waiting_queue_promoted_total을 증가시킨다")
    void recordPromoted_WithoutActiveTransaction_ShouldIncrementImmediately() {
        waitingQueueMetrics.recordPromoted();

        assertThat(meterRegistry.get("gymflow_waiting_queue_promoted_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("joined/cancelled/promoted counter는 서로 독립적으로 집계된다")
    void counters_ShouldBeIndependent() {
        waitingQueueMetrics.recordJoined();
        waitingQueueMetrics.recordJoined();
        waitingQueueMetrics.recordCancelled();
        waitingQueueMetrics.recordPromoted();
        waitingQueueMetrics.recordPromoted();
        waitingQueueMetrics.recordPromoted();

        assertThat(meterRegistry.get("gymflow_waiting_queue_joined_total").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get("gymflow_waiting_queue_cancelled_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("gymflow_waiting_queue_promoted_total").counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("gymflow_waiting_queue_size Gauge는 WaitingQueueRepository.countByStatus(WAITING)을 그대로 반영한다")
    void gauge_ShouldReflectWaitingCountFromRepository() {
        when(waitingQueueRepository.countByStatus(WaitingQueueStatus.WAITING)).thenReturn(4L);

        assertThat(meterRegistry.get("gymflow_waiting_queue_size").gauge().value()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("gymflow_waiting_queue_size Gauge는 별도의 mutable 상태를 캐싱하지 않고, 매 조회마다 Repository의 최신 값을 반영한다")
    void gauge_ShouldReflectLatestRepositoryValueOnEachRead_NotACachedMutableState() {
        when(waitingQueueRepository.countByStatus(WaitingQueueStatus.WAITING)).thenReturn(4L, 7L);

        assertThat(meterRegistry.get("gymflow_waiting_queue_size").gauge().value()).isEqualTo(4.0);
        assertThat(meterRegistry.get("gymflow_waiting_queue_size").gauge().value()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("모든 metric에는 userId/resourceId 등 고카디널리티 tag가 없다")
    void metrics_ShouldHaveNoHighCardinalityTags() {
        waitingQueueMetrics.recordJoined();
        waitingQueueMetrics.recordCancelled();
        waitingQueueMetrics.recordPromoted();

        assertThat(meterRegistry.get("gymflow_waiting_queue_joined_total").counter().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_waiting_queue_cancelled_total").counter().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_waiting_queue_promoted_total").counter().getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("gymflow_waiting_queue_size").gauge().getId().getTags()).isEmpty();
    }
}
