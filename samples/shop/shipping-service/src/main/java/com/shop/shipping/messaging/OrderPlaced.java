package com.shop.shipping.messaging;

public record OrderPlaced(Long orderId, String sku, int quantity) {
}
