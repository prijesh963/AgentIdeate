package com.shop.order.client;

import java.math.BigDecimal;

public record PriceResponse(String sku, BigDecimal amount) {
}
