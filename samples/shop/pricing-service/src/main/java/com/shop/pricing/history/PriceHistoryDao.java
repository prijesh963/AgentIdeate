package com.shop.pricing.history;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public class PriceHistoryDao {

    private final JdbcTemplate jdbcTemplate;

    public PriceHistoryDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BigDecimal> history(String sku) {
        return jdbcTemplate.queryForList(
                "select amount from prices where sku = ? order by valid_from", BigDecimal.class, sku);
    }
}
