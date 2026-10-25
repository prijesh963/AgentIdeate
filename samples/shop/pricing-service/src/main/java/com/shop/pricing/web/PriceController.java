package com.shop.pricing.web;

import com.shop.pricing.domain.Price;
import com.shop.pricing.service.PriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping("/{sku}")
    public Price getPrice(@PathVariable String sku) {
        return priceService.currentPrice(sku);
    }
}
