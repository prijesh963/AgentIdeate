package com.shop.inventory.web;

import com.shop.inventory.domain.StockItem;
import com.shop.inventory.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final InventoryService inventoryService;

    public StockController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{sku}")
    public StockItem stock(@PathVariable String sku) {
        return inventoryService.stockFor(sku);
    }

    @RequestMapping(value = "/{sku}/reserve", method = RequestMethod.POST)
    public StockItem reserve(@PathVariable String sku) {
        return inventoryService.reserve(sku, 1);
    }
}
