package com.shop.order.web;

import com.shop.order.config.ApiPaths;
import com.shop.order.domain.OrderEntity;
import com.shop.order.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.ORDERS)
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderEntity> getOrder(@PathVariable Long id) {
        return orderService.findOrder(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public OrderEntity placeOrder(@RequestBody OrderRequest request) {
        return orderService.placeOrder(request);
    }

    @PutMapping("/{id}/cancel")
    public OrderEntity cancelOrder(@PathVariable Long id) {
        return orderService.cancelOrder(id);
    }
}
