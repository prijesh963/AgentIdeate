package com.shop.inventory.messaging;

import com.shop.inventory.service.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCancelledListener {

    private final InventoryService inventoryService;

    public OrderCancelledListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = "${app.topics.order-cancelled}")
    public void onOrderCancelled(OrderCreatedMessage message) {
        inventoryService.release(message.sku(), message.quantity());
    }
}
