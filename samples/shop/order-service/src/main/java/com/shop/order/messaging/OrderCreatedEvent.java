package com.shop.order.messaging;

public record OrderCreatedEvent(Long orderId, String sku, int quantity) {
}
