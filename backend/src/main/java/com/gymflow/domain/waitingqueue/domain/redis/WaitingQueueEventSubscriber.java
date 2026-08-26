package com.gymflow.domain.waitingqueue.domain.redis;

import com.gymflow.domain.waitingqueue.domain.websocket.WaitingQueueDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class WaitingQueueEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public WaitingQueueEventSubscriber(
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        listenerContainer.addMessageListener(
                this, new ChannelTopic(WaitingQueueEventChannel.WAITING_QUEUE_EVENT));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (ObjectUtils.isEmpty(message.getBody())) {
            return;
        }

        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            WaitingQueuePromotedEvent event = objectMapper.readValue(payload, WaitingQueuePromotedEvent.class);
            log.info("WaitingQueue promoted event received. waitingQueueId={}", event.waitingQueueId());
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(event.userId()), WaitingQueueDestination.WAITING_QUEUE_QUEUE, event);
        } catch (RuntimeException e) {
            log.warn("WaitingQueue promoted event 처리에 실패했습니다. payload={}", payload, e);
        }
    }
}
