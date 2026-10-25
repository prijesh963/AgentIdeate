package com.shop.shipping.config;

import com.shop.shipping.messaging.DeliveryNotice;
import com.shop.shipping.messaging.OrderPlaced;
import com.shop.shipping.messaging.ShipmentDispatched;
import com.shop.shipping.service.ShipmentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Configuration
public class ShippingFunctions {

    @Bean
    public Consumer<OrderPlaced> shipOrder(ShipmentService shipmentService) {
        return order -> shipmentService.createShipment(order.orderId(), order.sku());
    }

    @Bean
    public Function<ShipmentDispatched, DeliveryNotice> notifyDelivery() {
        return dispatched -> new DeliveryNotice(dispatched.orderId(), "Your order is on its way");
    }

    /** Not listed in spring.cloud.function.definition, so Spring does not bind it. */
    @Bean
    public Supplier<String> heartbeat() {
        return () -> "shipping-alive";
    }
}
