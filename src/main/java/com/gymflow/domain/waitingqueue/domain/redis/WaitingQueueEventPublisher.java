package com.gymflow.domain.waitingqueue.domain.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class WaitingQueueEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(WaitingQueuePromotedEvent event) {
        String payload = objectMapper.writeValueAsString(event);
        redisTemplate.convertAndSend(WaitingQueueEventChannel.WAITING_QUEUE_EVENT, payload);
    }
}
