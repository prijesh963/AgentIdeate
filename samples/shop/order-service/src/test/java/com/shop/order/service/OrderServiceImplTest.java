package com.shop.order.service;

import com.shop.order.domain.OrderEntity;
import com.shop.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void findOrderReturnsRepositoryResult() {
        OrderEntity order = new OrderEntity();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThat(orderService.findOrder(1L)).contains(order);
    }
}
