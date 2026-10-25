package dev.agentideate.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private ToolRegistry.Outcome call(String tool, String... kv) {
        ObjectNode args = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return TestSupport.registry(home, TestSupport.shop()).call(tool, args);
    }

    @Test
    void overviewListsEachServiceWithRoutesBeansFeignAndConfig() {
        ToolRegistry.Outcome o = call("repo_overview");
        assertFalse(o.error(), o.text());
        String t = o.text();
        assertTrue(t.contains("3 Spring Boot services"), t);
        assertTrue(t.contains("Service order-service [gradle]"), t);
        assertTrue(t.contains("Service inventory-service [maven]"), t);
        assertTrue(t.contains("Service pricing-service [gradle]"), t);
        assertTrue(t.contains("PUT    /api/orders/{id}/cancel  cancelOrder"), t);
        assertTrue(t.contains("calls out: PricingClient -> pricing-service  GET /api/prices/{sku}"), t);
        assertTrue(t.contains("order-service/src/main/resources/application.yml"), t);
        assertTrue(t.indexOf("Service inventory-service") < t.indexOf("Service order-service"), "services sorted");
    }

    @Test
    void locateFindsByWordsAndRanksTheExactNameFirst() {
        String t = call("locate", "query", "OrderServiceImpl").text();
        String first = t.lines().skip(1).findFirst().orElseThrow();
        assertTrue(first.contains("class  OrderServiceImpl [service]"), t);
        assertTrue(first.contains("order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:"), t);

        String words = call("locate", "query", "where is stock reserved").text();
        assertTrue(words.contains("InventoryService#reserve(String sku, int quantity)"), words);
        assertTrue(words.contains("POST /api/stock/{sku}/reserve"), words);
    }

    @Test
    void locateSaysPlainlyWhenNothingExists() {
        ToolRegistry.Outcome o = call("locate", "query", "invoice approval");
        assertFalse(o.error());
        assertTrue(o.text().startsWith("Not found: nothing named like \"invoice approval\""), o.text());
        assertTrue(o.text().contains("Not searched:"), o.text());
    }

    @Test
    void locateKindFilterAndValidation() {
        String routes = call("locate", "query", "orders", "kind", "route").text();
        assertTrue(routes.lines().skip(1).filter(l -> l.startsWith("  ")).allMatch(l -> l.trim().startsWith("route")), routes);
        assertTrue(call("locate", "query", "x", "kind", "banana").error());
        assertTrue(call("locate").error());
    }

    @Test
    void flowFollowsInterfaceToImplAndFeignIntoTheOtherService() {
        String t = call("endpoint_flow", "path", "/api/orders", "method", "POST").text();
        assertTrue(t.contains("OrderController#placeOrder"), t);
        assertTrue(t.contains("OrderService.placeOrder -> OrderServiceImpl#placeOrder"), t);
        assertTrue(t.contains("PricingClient#getPrice [feign -> pricing-service GET /api/prices/{sku}]"), t);
        assertTrue(t.contains("=> handled in pricing-service:"), t);
        assertTrue(t.contains("PriceService#currentPrice"), t);
        assertTrue(t.contains("OrderRepository#save [spring-data-repository, inherited from Spring Data]"), t);
        assertTrue(t.contains("OrderEventPublisher#publishCreated"), t);
        assertTrue(t.contains("uses data: OrderEntity, OrderRequest, PriceResponse"), t);
        assertFalse(t.contains("setCustomerId"), "accessor calls are summarized, not listed: " + t);
    }

    @Test
    void flowMatchesAConcretePathAgainstATemplate() {
        String t = call("endpoint_flow", "path", "/api/orders/42/cancel").text();
        assertTrue(t.startsWith("PUT /api/orders/{id}/cancel"), t);
    }

    @Test
    void flowReportsAMissingRouteWithoutInventingOne() {
        ToolRegistry.Outcome o = call("endpoint_flow", "path", "/api/invoices/{id}/approve");
        assertFalse(o.error());
        assertTrue(o.text().startsWith("Not found: no route matches /api/invoices/{id}/approve among 6 routes"), o.text());
    }

    @Test
    void everyAnswerCarriesTheSavingsFooterAndIsLedgered() throws Exception {
        String t = call("endpoint_flow", "path", "/api/orders/{id}", "method", "GET").text();
        assertTrue(t.lines().reduce((a, b) -> b).orElseThrow()
                .matches("-- agentideate: from \\d+ source files \\(~[\\d.k]+ tokens to open\\) -> ~[\\d.k]+ tokens here; index 29 Java files.*"), t);
        try (Stream<Path> s = Files.walk(home)) {
            Path ledger = s.filter(p -> p.getFileName().toString().equals("ledger.jsonl")).findFirst().orElseThrow();
            String row = Files.readAllLines(ledger).get(0);
            assertTrue(row.contains("\"tool\":\"endpoint_flow\""), row);
            assertTrue(row.contains("\"referencedFiles\":3"), row);
        }
    }

    @Test
    void anAnswerOverBudgetIsCutAndSaysSo() {
        Answer a = new Answer(dev.agentideate.workspace.Workspace.of(List.of(home)), 25);
        a.line("0123456789").line("0123456789").line("0123456789").line("0123456789");
        assertEquals(2, a.droppedLines());
        assertTrue(a.body().endsWith("[+2 more lines not shown - narrow the query]\n"), a.body());
    }

    @Test
    void unknownToolAndMissingRepoAreErrorsWithAWayForward(@TempDir Path elsewhere) {
        assertTrue(call("nope").text().startsWith("Unknown tool 'nope'. Available: repo_overview, locate, endpoint_flow, messaging_map, data_map, impact_of"));
        ToolRegistry r = ToolRegistry.standard(new dev.agentideate.workspace.WorkspaceService(
                new dev.agentideate.AppHome(home), List.of(), elsewhere));
        ToolRegistry.Outcome o = r.call("repo_overview", JSON.createObjectNode());
        assertTrue(o.error());
        assertTrue(o.text().contains("'repo' argument"), o.text());
    }

    @Test
    void multiRootAnswersPrefixPathsWithTheirRoot(@TempDir Path tmp) throws Exception {
        Path shop = TestSupport.shop();
        ToolRegistry r = TestSupport.registry(home, shop.resolve("order-service"), shop.resolve("pricing-service"));
        String t = r.call("endpoint_flow", JSON.createObjectNode().put("path", "/api/orders").put("method", "POST")).text();
        assertTrue(t.contains("order-service/src/main/java/com/shop/order/web/OrderController.java:"), t);
        assertTrue(t.contains("=> handled in pricing-service:"), "cross-repo Feign hop: " + t);
        assertTrue(t.contains("pricing-service/src/main/java/com/shop/pricing/service/PriceService.java:"), t);
    }

    @Test
    void theRepoArgumentOverridesTheConfiguredRoots(@TempDir Path tmp) {
        ToolRegistry r = TestSupport.registry(home, tmp);
        String t = r.call("repo_overview", JSON.createObjectNode().put("repo", TestSupport.shop().toString())).text();
        assertTrue(t.contains("3 Spring Boot services"), t);
    }

    @Test
    void overviewShowsDataAndMessagingPerService() {
        String t = call("repo_overview").text();
        assertTrue(t.contains("  data: postgresql db \"orders\" · orders"), t);
        assertTrue(t.contains("  data: mongodb db \"inventory\" · stock"), t);
        assertTrue(t.contains("  messaging: produces kafka order.cancelled, kafka order.created · consumes -"), t);
        assertTrue(t.contains("  messaging: produces - · consumes kafka order.cancelled, kafka order.created"), t);
    }

    @Test
    void messagingMapPairsProducersWithConsumers() {
        String t = call("messaging_map").text();
        assertTrue(t.startsWith("kafka order.cancelled\n  produced by  order-service  OrderEventPublisher#publishCancelled"), t);
        assertTrue(t.contains("  consumed by  inventory-service (group inventory)  OrderCancelledListener#onOrderCancelled"), t);
        String filtered = call("messaging_map", "channel", "created").text();
        assertFalse(filtered.contains("order.cancelled"), filtered);
        assertTrue(call("messaging_map", "channel", "payments").text().startsWith("Not found: no channel containing \"payments\""));
    }

    @Test
    void dataMapListsEntitiesReposMigrationsAndDirectAccess() {
        String t = call("data_map").text();
        assertTrue(t.contains("order-service  postgresql db \"orders\""), t);
        assertTrue(t.contains("  table orders <- OrderEntity"), t);
        assertTrue(t.contains("    repo OrderRepository"), t);
        assertTrue(t.contains("    migrations: order-service/src/main/resources/db/migration/V1__create_orders.sql, "
                + "order-service/src/main/resources/db/migration/V2__add_order_status.sql"), t);
        assertTrue(t.contains("  collection stock <- StockItem"), t);
        assertTrue(t.contains("    migrations: none found for this table"), "prices has no migration: " + t);
        assertTrue(t.contains("  direct JdbcTemplate in PriceHistoryDao"), t);
        String one = call("data_map", "name", "stock").text();
        assertFalse(one.contains("orders"), one);
        assertTrue(call("data_map", "name", "invoice").text().startsWith("Not found: no entity, table or collection named like \"invoice\""));
    }

    @Test
    void flowFollowsKafkaIntoTheConsumingService() {
        String t = call("endpoint_flow", "path", "/api/orders/{id}/cancel").text();
        assertTrue(t.contains("-> kafka order.cancelled  order-service/src/main/java/com/shop/order/messaging/OrderEventPublisher.java:22"), t);
        assertTrue(t.contains("=> consumed in inventory-service (group inventory):"), t);
        assertTrue(t.contains("OrderCancelledListener#onOrderCancelled"), t);
        assertTrue(t.contains("InventoryService#release"), t);
    }

    @Test
    void impactOfAMethod() {
        String t = call("impact_of", "target", "OrderServiceImpl#placeOrder").text();
        assertTrue(t.startsWith("Impact of OrderServiceImpl#placeOrder  (order-service)"), t);
        assertTrue(t.contains("Called by (1):\n  OrderController#placeOrder"), t);
        assertTrue(t.contains("Reached from (1):\n  route POST /api/orders  (order-service)"), t);
        assertTrue(t.contains("  kafka order.created -> inventory-service OrderCreatedListener#onOrderCreated"), t);
        assertTrue(t.contains("Tests (1):\n  OrderServiceImplTest"), t);
        assertTrue(call("impact_of", "target", "OrderService.placeOrder").text().contains("Called by (1)"));
    }

    @Test
    void impactOfAnInterfaceAndOfAConsumerCrossesServices() {
        String iface = call("impact_of", "target", "OrderService").text();
        assertTrue(iface.contains("interface · implemented by OrderServiceImpl"), iface);
        assertTrue(iface.contains("Injected into (1):\n  OrderController (constructor)"), iface);
        assertTrue(iface.contains("Tests (1):\n  OrderServiceImplTest"), "tests of the implementation count: " + iface);

        String inv = call("impact_of", "target", "InventoryService#reserve").text();
        assertTrue(inv.contains("  kafka order.created consumed by OrderCreatedListener#onOrderCreated  (inventory-service)"), inv);
        assertTrue(inv.contains("  route POST /api/stock/{sku}/reserve  (inventory-service)"), inv);
    }

    @Test
    void impactOfATopicAndOfATable() {
        String topic = call("impact_of", "target", "order.created").text();
        assertTrue(topic.startsWith("Impact of kafka order.created"), topic);
        assertTrue(topic.contains("Produced when (1):\n  route POST /api/orders  (order-service)"), topic);
        assertTrue(topic.contains("Consumers (1):\n  inventory-service (group inventory)  OrderCreatedListener#onOrderCreated"), topic);

        String table = call("impact_of", "target", "orders").text();
        assertTrue(table.startsWith("orders is the table of OrderEntity."), table);
        assertTrue(table.contains("jpa entity"), table);
        assertTrue(table.contains("  migration order-service/src/main/resources/db/migration/V1__create_orders.sql"), table);
    }

    @Test
    void impactSaysWhatItCannotFindAndNeverGuessesBetweenNamesakes(@TempDir Path tmp) throws Exception {
        assertTrue(call("impact_of", "target", "InvoiceService").text().startsWith("Not found: no class, method, topic/queue or table named \"InvoiceService\""));
        ToolRegistry.Outcome noMethod = call("impact_of", "target", "OrderServiceImpl#approve");
        assertTrue(noMethod.error());
        assertTrue(noMethod.text().contains("has no method 'approve'"), noMethod.text());

        for (String svc : List.of("a", "b")) {
            Path dir = tmp.resolve(svc + "/src/main/java/" + svc);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("Client.java"), "package " + svc + "; class Client {}");
        }
        String t = TestSupport.registry(home, tmp).call("impact_of", JSON.createObjectNode().put("target", "Client")).text();
        assertTrue(t.startsWith("\"Client\" is ambiguous: 2 types"), t);
        assertTrue(t.contains("  a.Client") && t.contains("  b.Client"), t);
        assertTrue(TestSupport.registry(home, tmp).call("impact_of", JSON.createObjectNode().put("target", "a.Client")).text()
                .startsWith("Impact of Client"));
    }

    @Test
    void locateFindsTopicsAndTables() {
        String t = call("locate", "query", "order cancelled", "kind", "topic").text();
        assertTrue(t.contains("topic  kafka order.cancelled"), t);
        String tables = call("locate", "query", "stock", "kind", "table").text();
        assertTrue(tables.contains("table  collection stock <- StockItem"), tables);
    }
}
