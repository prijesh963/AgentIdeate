package com.shop.order.web;

public record OrderRequest(String customerId, String sku, int quantity) {
}
