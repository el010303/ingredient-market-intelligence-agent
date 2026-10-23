package com.marketintel.marketintelligenceagent;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public EventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishSummarize(Long eventId) {
        send(RabbitConfig.RK_SUMMARIZE, eventId);
    }

    public void publishEmbed(Long eventId) {
        send(RabbitConfig.RK_EMBED, eventId);
    }

    private void send(String routingKey, Long eventId) {
        String body = "{\"eventId\":" + eventId + "}";
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);  // broker 重启后消息不丢
        rabbitTemplate.send(RabbitConfig.EXCHANGE, routingKey,
                new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }
}