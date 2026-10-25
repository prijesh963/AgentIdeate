package com.shop.shipping.service;

import com.shop.shipping.messaging.ShipmentDispatched;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

@Service
public class ShipmentService {

    private final StreamBridge streamBridge;

    public ShipmentService(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public void createShipment(Long orderId, String sku) {
        // carrier booking would happen here
        streamBridge.send("shipmentDispatched-out-0", new ShipmentDispatched(orderId, sku));
    }
}
