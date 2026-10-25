package com.shop.inventory.repository;

import com.shop.inventory.domain.StockItem;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface StockRepository extends MongoRepository<StockItem, String> {

    Optional<StockItem> findBySku(String sku);
}
