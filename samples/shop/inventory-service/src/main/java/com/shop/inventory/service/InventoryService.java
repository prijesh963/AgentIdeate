package com.shop.inventory.service;

import com.shop.inventory.domain.StockItem;
import com.shop.inventory.repository.StockRepository;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private final StockRepository stockRepository;

    public InventoryService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public StockItem stockFor(String sku) {
        return stockRepository.findBySku(sku)
                .orElseThrow(() -> new IllegalArgumentException("Unknown sku " + sku));
    }

    public StockItem reserve(String sku, int quantity) {
        StockItem item = stockFor(sku);
        item.setAvailable(item.getAvailable() - quantity);
        return stockRepository.save(item);
    }
}
