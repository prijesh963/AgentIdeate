package com.shop.order.service;

import com.shop.order.domain.OrderEntity;
import com.shop.order.web.OrderRequest;

import java.util.Optional;

public interface OrderService {

    Optional<OrderEntity> findOrder(Long id);

    OrderEntity placeOrder(OrderRequest request);

    OrderEntity cancelOrder(Long id);
}
