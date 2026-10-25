package com.shop.pricing.repository;

import com.shop.pricing.domain.Price;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PriceRepository extends JpaRepository<Price, Long> {

    Optional<Price> findTopBySkuOrderByValidFromDesc(String sku);
}
