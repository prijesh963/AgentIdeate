package com.shop.order.service;

import com.shop.order.client.PricingClient;
import com.shop.order.client.PriceResponse;
import com.shop.order.domain.OrderEntity;
import com.shop.order.domain.OrderStatus;
import com.shop.order.messaging.OrderEventPublisher;
import com.shop.order.repository.OrderRepository;
import com.shop.order.web.OrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;
    private final PricingClient pricingClient;

    @Override
    public Optional<OrderEntity> findOrder(Long id) {
        return orderRepository.findById(id);
    }

    @Override
    @Transactional
    public OrderEntity placeOrder(OrderRequest request) {
        PriceResponse price = pricingClient.getPrice(request.sku());
        OrderEntity order = new OrderEntity();
        order.setCustomerId(request.customerId());
        order.setSku(request.sku());
        order.setQuantity(request.quantity());
        order.setTotal(total(price.amount(), request.quantity()));
        order.setStatus(OrderStatus.CREATED);
        OrderEntity saved = orderRepository.save(order);
        eventPublisher.publishCreated(saved);
        return saved;
    }

    @Override
    @Transactional
    public OrderEntity cancelOrder(Long id) {
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No order " + id));
        order.setStatus(OrderStatus.CANCELLED);
        eventPublisher.publishCancelled(order);
        return orderRepository.save(order);
    }

    private BigDecimal total(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
