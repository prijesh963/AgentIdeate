package com.shop.shipping.messaging;

public record ShipmentDispatched(Long orderId, String sku) {
}
