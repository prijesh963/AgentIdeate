package com.shop.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "pricing-service", url = "${pricing.url}")
public interface PricingClient {

    @GetMapping("/api/prices/{sku}")
    PriceResponse getPrice(@PathVariable("sku") String sku);
}
