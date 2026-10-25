package com.shop.order.messaging;

import com.shop.order.config.KafkaTopics;
import com.shop.order.domain.OrderEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventPublisher {

    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishCreated(OrderEntity order) {
        kafkaTemplate.send(KafkaTopics.ORDER_CREATED, order.getSku(), toEvent(order));
    }

    public void publishCancelled(OrderEntity order) {
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, order.getSku(), toEvent(order));
    }

    private OrderCreatedEvent toEvent(OrderEntity order) {
        return new OrderCreatedEvent(order.getId(), order.getSku(), order.getQuantity());
    }
}
