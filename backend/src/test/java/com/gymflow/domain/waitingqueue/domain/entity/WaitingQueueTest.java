package com.gymflow.domain.waitingqueue.domain.entity;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.waitingqueue.domain.enumtype.WaitingQueueStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitingQueueTest {

    private User createUser() {
        return User.builder()
                .email("test@gymflow.com")
                .password("securePassword123")
                .name("John Doe")
                .build();
    }

    private Resource createResource() {
        return Resource.builder()
                .name("Chest Press A-1")
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
    }

    private WaitingQueue createWaitingQueue() {
        return WaitingQueue.builder()
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build();
    }

    @Test
    @DisplayName("WaitingQueue는 User, Resource, 시간 범위로 정상 생성되고 초기 상태는 WAITING이다")
    void createWaitingQueue_WithValidData_ShouldSucceed() {
        // given
        User user = createUser();
        Resource resource = createResource();
        LocalDateTime startAt = LocalDateTime.of(2026, 8, 12, 14, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 8, 12, 14, 15);

        // when
        WaitingQueue waitingQueue = WaitingQueue.builder()
                .user(user)
                .resource(resource)
                .startAt(startAt)
                .endAt(endAt)
                .build();

        // then
        assertThat(waitingQueue.getUser()).isSameAs(user);
        assertThat(waitingQueue.getResource()).isSameAs(resource);
        assertThat(waitingQueue.getStartAt()).isEqualTo(startAt);
        assertThat(waitingQueue.getEndAt()).isEqualTo(endAt);
        assertThat(waitingQueue.getStatus()).isEqualTo(WaitingQueueStatus.WAITING);
    }

    @Test
    @DisplayName("user가 null이면 WaitingQueue 생성에 실패한다")
    void createWaitingQueue_WithNullUser_ShouldThrowException() {
        assertThatThrownBy(() -> WaitingQueue.builder()
                .user(null)
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User");
    }

    @Test
    @DisplayName("resource가 null이면 WaitingQueue 생성에 실패한다")
    void createWaitingQueue_WithNullResource_ShouldThrowException() {
        assertThatThrownBy(() -> WaitingQueue.builder()
                .user(createUser())
                .resource(null)
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 15))
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Resource");
    }

    @Test
    @DisplayName("startAt이 endAt보다 이후이면 WaitingQueue 생성에 실패한다")
    void createWaitingQueue_WithStartAtAfterEndAt_ShouldThrowException() {
        assertThatThrownBy(() -> WaitingQueue.builder()
                .user(createUser())
                .resource(createResource())
                .startAt(LocalDateTime.of(2026, 8, 12, 14, 30))
                .endAt(LocalDateTime.of(2026, 8, 12, 14, 0))
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("startAt이 endAt과 같으면 WaitingQueue 생성에 실패한다")
    void createWaitingQueue_WithStartAtEqualToEndAt_ShouldThrowException() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 12, 14, 0);

        assertThatThrownBy(() -> WaitingQueue.builder()
                .user(createUser())
                .resource(createResource())
                .startAt(sameTime)
                .endAt(sameTime)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("WAITING 상태의 WaitingQueue는 취소하면 CANCELLED가 된다")
    void cancel_WithWaitingStatus_ShouldChangeToCancelled() {
        // given
        WaitingQueue waitingQueue = createWaitingQueue();

        // when
        waitingQueue.cancel();

        // then
        assertThat(waitingQueue.getStatus()).isEqualTo(WaitingQueueStatus.CANCELLED);
    }

    @Test
    @DisplayName("WAITING 상태의 WaitingQueue는 승급하면 PROMOTED가 된다")
    void promote_WithWaitingStatus_ShouldChangeToPromoted() {
        // given
        WaitingQueue waitingQueue = createWaitingQueue();

        // when
        waitingQueue.promote();

        // then
        assertThat(waitingQueue.getStatus()).isEqualTo(WaitingQueueStatus.PROMOTED);
    }

    @Test
    @DisplayName("이미 CANCELLED 상태인 WaitingQueue는 다시 취소할 수 없다")
    void cancel_WithAlreadyCancelledStatus_ShouldThrowException() {
        // given
        WaitingQueue waitingQueue = createWaitingQueue();
        waitingQueue.cancel();

        // when & then
        assertThatThrownBy(waitingQueue::cancel)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PROMOTED 상태인 WaitingQueue는 취소할 수 없다")
    void cancel_WithPromotedStatus_ShouldThrowException() {
        // given
        WaitingQueue waitingQueue = createWaitingQueue();
        waitingQueue.promote();

        // when & then
        assertThatThrownBy(waitingQueue::cancel)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CANCELLED 상태인 WaitingQueue는 승급할 수 없다")
    void promote_WithCancelledStatus_ShouldThrowException() {
        // given
        WaitingQueue waitingQueue = createWaitingQueue();
        waitingQueue.cancel();

        // when & then
        assertThatThrownBy(waitingQueue::promote)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 PROMOTED 상태인 WaitingQueue는 다시 승급할 수 없다")
    void promote_WithAlreadyPromotedStatus_ShouldThrowException() {
        // given
        WaitingQueue waitingQueue = createWaitingQueue();
        waitingQueue.promote();

        // when & then
        assertThatThrownBy(waitingQueue::promote)
                .isInstanceOf(IllegalStateException.class);
    }
}
