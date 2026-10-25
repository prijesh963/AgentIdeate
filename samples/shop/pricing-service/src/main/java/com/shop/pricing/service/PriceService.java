package com.shop.pricing.service;

import com.shop.pricing.domain.Price;
import com.shop.pricing.repository.PriceRepository;
import org.springframework.stereotype.Service;

@Service
public class PriceService {

    private final PriceRepository priceRepository;

    public PriceService(PriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    public Price currentPrice(String sku) {
        return priceRepository.findTopBySkuOrderByValidFromDesc(sku)
                .orElseThrow(() -> new IllegalArgumentException("No price for " + sku));
    }
}
