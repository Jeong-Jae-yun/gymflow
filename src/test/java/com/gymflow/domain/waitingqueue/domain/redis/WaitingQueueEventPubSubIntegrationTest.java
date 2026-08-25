package com.gymflow.domain.waitingqueue.domain.redis;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gymflow.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 Testcontainers Redis + 실제 Spring 컨텍스트(RedisMessageListenerContainer,
 * WaitingQueueEventSubscriber)를 사용해 Publisher.publish(...)로 발행한 메시지가
 * 실제 Redis Pub/Sub Channel을 거쳐 Subscriber에 전달되는지 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WaitingQueueEventPubSubIntegrationTest {

    @Autowired
    private WaitingQueueEventPublisher waitingQueueEventPublisher;

    private Logger subscriberLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        subscriberLogger = (Logger) LoggerFactory.getLogger(WaitingQueueEventSubscriber.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        subscriberLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        subscriberLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("Publisher가 발행한 승급 이벤트를 실제 Subscriber가 Redis Pub/Sub으로 수신한다")
    void publish_ShouldBeReceivedByRealSubscriberOverRedis() {
        // given
        WaitingQueuePromotedEvent event = new WaitingQueuePromotedEvent(
                10L, 3L, 101L,
                LocalDateTime.of(2026, 8, 20, 14, 0),
                LocalDateTime.of(2026, 8, 20, 14, 15),
                LocalDateTime.now());

        // when
        waitingQueueEventPublisher.publish(event);

        // then
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(logAppender.list)
                        .anyMatch(loggedEvent -> loggedEvent.getFormattedMessage()
                                .contains("WaitingQueue promoted event received")));
    }
}
