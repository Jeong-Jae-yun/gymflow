package com.gymflow.domain.waitingqueue.domain.websocket;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.domain.enumtype.CancelReason;
import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.service.ReservationService;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;
import com.gymflow.domain.waitingqueue.service.WaitingQueueService;
import com.gymflow.global.security.jwt.JwtTokenProvider;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 통합 테스트 중 재현된 race condition에 대한 회귀 테스트:
 *
 * PromotionProcessor.tryPromote()가 (1) WaitingQueue를 PROMOTED로 바꾸고 (2) WaitingQueuePromotion을
 * save한 뒤 (3) WaitingQueue 승급 WebSocket 이벤트를 발행하는데, 과거에는 (3)이 트랜잭션 commit *이전에*
 * 실행되어, WebSocket 알림을 받은 프론트가 곧바로 GET /api/waiting-queues를 재조회하면 아직 commit되지
 * 않은 Promotion을 읽지 못해 promotionId=null로 응답받는 문제가 있었다.
 *
 * WaitingQueueEventPublisher.publish()가 이제 활성 트랜잭션의 afterCommit 시점까지 실제 발행을 미루므로,
 * 이 테스트는 "WebSocket 이벤트를 수신한 시점에는 이미 DB에서 promotionId를 조회할 수 있다"를
 * end-to-end로 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PromotionWebSocketCommitOrderingIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private WaitingQueueService waitingQueueService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Long persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .password("securePassword123")
                .name("Commit Ordering Tester")
                .build()).getId();
    }

    private Long persistResourceWithPolicy(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(10)
                .maxDuration(60)
                .build();
        return resourceRepository.save(resource).getId();
    }

    private void authenticateAs(Long userId) {
        CustomUserDetails principal = new CustomUserDetails(userId, "user-" + userId + "@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    @DisplayName("WaitingQueue 승급 WebSocket 이벤트를 수신한 시점에 곧바로 재조회해도 promotionId가 이미 채워져 있다")
    void promotedEvent_ByTheTimeItArrives_WaitingQueueApiAlreadyReflectsPromotion() throws Exception {
        // given: A가 예약하고 B가 같은 시간대에 대기열로 등록한다
        Long resourceId = persistResourceWithPolicy("Commit Ordering Resource " + System.nanoTime());
        Long userAId = persistUser("commit-ordering-a-" + System.nanoTime() + "@gymflow.com");
        Long userBId = persistUser("commit-ordering-b-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime startAt = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime endAt = startAt.plusMinutes(15);

        authenticateAs(userAId);
        ReservationResponse createdByA =
                reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        SecurityContextHolder.clearContext();

        authenticateAs(userBId);
        waitingQueueService.registerWaitingQueue(new WaitingQueueCreateRequest(resourceId, startAt, endAt));
        SecurityContextHolder.clearContext();

        // B가 STOMP로 연결해 자신의 승급 큐를 구독한다
        String tokenB = jwtTokenProvider.createAccessToken(userBId, "commit-ordering-b@gymflow.com", UserRole.USER);
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + tokenB);

        StompSession session = stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);

        CountDownLatch eventReceived = new CountDownLatch(1);
        AtomicReference<Long> promotionIdAtEventTime = new AtomicReference<>();

        session.subscribe("/user" + WaitingQueueDestination.WAITING_QUEUE_QUEUE, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // 실제 프론트가 이벤트 수신 즉시 waiting-queues를 재조회하는 것과 동일한 타이밍을 재현한다.
                authenticateAs(userBId);
                try {
                    Page<WaitingQueueResponse> page =
                            waitingQueueService.getMyWaitingQueues(PageRequest.of(0, 10));
                    page.getContent().stream()
                            .filter(item -> item.resourceId().equals(resourceId))
                            .findFirst()
                            .ifPresent(item -> promotionIdAtEventTime.set(item.promotionId()));
                } finally {
                    SecurityContextHolder.clearContext();
                }
                eventReceived.countDown();
            }
        });

        // when: A가 예약을 취소해 B에게 승급 기회가 발생한다
        authenticateAs(userAId);
        reservationService.cancelReservation(createdByA.reservationId(), new CancelReservationRequest(CancelReason.PERSONAL_REASON));
        SecurityContextHolder.clearContext();

        // then: WebSocket 이벤트를 받은 바로 그 시점의 재조회에서 이미 promotionId가 채워져 있어야 한다
        boolean received = eventReceived.await(10, TimeUnit.SECONDS);
        assertThat(received).as("승급 WebSocket 이벤트를 수신하지 못했다").isTrue();
        assertThat(promotionIdAtEventTime.get())
                .as("WebSocket 이벤트 수신 시점의 재조회에서 promotionId가 null이면 commit 이전에 이벤트가 발행된 것이다")
                .isNotNull();

        session.disconnect();
    }
}
