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
                .matches("-- agentideate: from \\d+ source files \\(~[\\d.k]+ tokens to open\\) -> ~[\\d.k]+ tokens here; index 27 Java files.*"), t);
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
        assertTrue(call("nope").text().startsWith("Unknown tool 'nope'. Available: repo_overview, locate, endpoint_flow"));
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
}
