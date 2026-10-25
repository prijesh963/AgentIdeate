package com.shop.inventory.messaging;

public record OrderCreatedMessage(Long orderId, String sku, int quantity) {
}
