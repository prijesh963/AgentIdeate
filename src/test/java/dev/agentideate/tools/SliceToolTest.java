package dev.agentideate.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentideate.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliceToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private String slice(String task) {
        ToolRegistry.Outcome o = TestSupport.registry(home, TestSupport.shop())
                .call("slice_for_task", JSON.createObjectNode().put("task", task).put("thread", "new"));
        assertFalse(o.error(), o.text());
        return o.text();
    }

    @Test
    void aTaskGetsItsServiceCollaboratorsDataAndKeyMethodBodies() {
        String t = slice("add a discount when placing an order");
        assertTrue(t.startsWith("Slice for: add a discount when placing an order\nEntry points:"), t);
        assertTrue(t.contains("route POST /api/orders -> OrderController#placeOrder"), t);
        assertTrue(t.contains("== OrderServiceImpl [service]  order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:17-58"), t);
        assertTrue(t.contains("  inject: OrderRepository orderRepository, OrderEventPublisher eventPublisher, PricingClient pricingClient"), t);
        assertTrue(t.contains(":30  @Override @Transactional OrderEntity placeOrder(OrderRequest request)  -> PricingClient.getPrice, OrderRepository.save, OrderEventPublisher.publishCreated"), t);
        assertTrue(t.contains("== OrderEntity (collaborator)"), t);
        assertTrue(t.contains("  table orders: Long id, String customerId, String sku, int quantity, BigDecimal total, OrderStatus status"), t);
        assertTrue(t.contains("@RequestMapping(/api/orders)"), "annotation constants resolved: " + t);
        assertTrue(t.contains("-- OrderServiceImpl#placeOrder  order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:30-43"), t);
        assertTrue(t.contains("  38 |         order.setTotal(total(price.amount(), request.quantity()));"), t);
        assertFalse(t.contains("== OrderPlaced"), "a record that merely shares words is not a seed: " + t);
        assertFalse(t.contains("-- OrderController#cancelOrder"), "weakly matching bodies are left out: " + t);
    }

    @Test
    void messagingShowsInTheSkeleton() {
        String t = slice("publish order created event");
        assertTrue(t.contains(":17  void publishCreated(OrderEntity order)  => kafka order.created"), t);
    }

    @Test
    void aTaskWithNothingToMatchSaysSo() {
        String t = slice("payroll tax report");
        assertTrue(t.startsWith("Not found: nothing in the index matches the words of this task (payroll, tax, report)."), t);
    }

    @Test
    void stemmingLetsTaskWordsMeetMethodNames() {
        assertEquals(LocateTool.stem("placing"), LocateTool.stem("place"));
        assertEquals(LocateTool.stem("orders"), LocateTool.stem("order"));
        assertEquals(List.of("discount", "placing", "order"), LocateTool.queryTokens("add a discount when placing an order"));
    }
}
