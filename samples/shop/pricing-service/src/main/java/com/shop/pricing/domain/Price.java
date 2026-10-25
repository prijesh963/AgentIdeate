package com.shop.pricing.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "prices")
public class Price {

    @Id
    private Long id;

    private String sku;

    private BigDecimal amount;

    private Instant validFrom;
}
