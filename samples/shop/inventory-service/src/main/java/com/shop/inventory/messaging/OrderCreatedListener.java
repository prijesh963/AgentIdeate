package com.shop.inventory.messaging;

import com.shop.inventory.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {

    @Autowired
    private InventoryService inventoryService;

    @KafkaListener(topics = "order.created", groupId = "inventory")
    public void onOrderCreated(OrderCreatedMessage message) {
        inventoryService.reserve(message.sku(), message.quantity());
    }
}
