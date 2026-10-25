package dev.agentideate.graph;

import dev.agentideate.TestSupport;
import dev.agentideate.graph.CodeGraph.Call;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.WorkspaceIndex;
import dev.agentideate.workspace.Workspace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGraphTest {

    static CodeGraph graph;

    @BeforeAll
    static void build(@TempDir Path state) throws Exception {
        graph = graphOf(state, TestSupport.shop());
    }

    static CodeGraph graphOf(Path state, Path... roots) throws Exception {
        WorkspaceIndex index = new WorkspaceIndex(Workspace.of(List.of(roots)), state);
        index.refresh();
        return new CodeGraph(index.workspace(), index.files());
    }

    static TypeNode type(CodeGraph g, String simple) {
        List<TypeNode> found = g.bySimpleName(simple);
        assertEquals(1, found.size(), simple);
        return found.get(0);
    }

    @Test
    void servicesAreModulesWithASpringBootApplication() {
        assertEquals(List.of("inventory-service", "order-service", "pricing-service"),
                graph.services().keySet().stream().sorted().toList());
    }

    @Test
    void beanKinds() {
        assertEquals("controller", graph.beanKind(type(graph, "OrderController")));
        assertEquals("service", graph.beanKind(type(graph, "OrderServiceImpl")));
        assertEquals("spring-data-repository", graph.beanKind(type(graph, "OrderRepository")));
        assertEquals("spring-data-repository", graph.beanKind(type(graph, "StockRepository")));
        assertEquals("feign-client", graph.beanKind(type(graph, "PricingClient")));
        assertEquals("component", graph.beanKind(type(graph, "OrderEventPublisher")));
        assertNull(graph.beanKind(type(graph, "OrderEntity")));
        assertNull(graph.beanKind(type(graph, "OrderService")), "a plain interface is not itself a bean");
    }

    @Test
    void endpointPathsResolveConstantsAcrossFilesAndConcatenation() {
        List<String> routes = graph.endpoints().stream().map(e -> e.httpMethod() + " " + e.path()).toList();
        assertEquals(List.of(
                "POST /api/orders", "GET /api/orders/{id}", "PUT /api/orders/{id}/cancel",
                "GET /api/prices/{sku}", "GET /api/stock/{sku}", "POST /api/stock/{sku}/reserve"), routes);
    }

    @Test
    void feignMappingsAreCallsOutNotRoutes() {
        assertEquals(List.of("GET /api/prices/{sku}"),
                graph.clientEndpoints().stream().map(e -> e.httpMethod() + " " + e.path()).toList());
        assertTrue(graph.endpoints().stream().noneMatch(e -> e.owner().name().equals("PricingClient")));
    }

    @Test
    void anInterfaceResolvesToItsImplementation() {
        assertEquals(List.of("OrderServiceImpl"),
                graph.implementations(type(graph, "OrderService")).stream().map(TypeNode::name).toList());
    }

    @Test
    void calleesDispatchThroughInterfacesAndKeepSpringDataCallsOnTheRepository() {
        TypeNode controller = type(graph, "OrderController");
        List<Call> calls = graph.callees(controller, controller.method("placeOrder"));
        assertEquals(1, calls.size());
        assertEquals("OrderServiceImpl", calls.get(0).target().name());
        assertEquals("OrderService", calls.get(0).via().name());

        TypeNode impl = type(graph, "OrderServiceImpl");
        List<String> targets = graph.callees(impl, impl.method("findOrder")).stream()
                .map(c -> c.target().name() + "#" + c.methodName() + (c.method() == null ? " (inherited)" : "")).toList();
        assertEquals(List.of("OrderRepository#findById (inherited)"), targets);
    }

    @Test
    void anUnresolvableConstantStaysVisiblyUnresolved(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("r");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("C.java"), """
                @RestController @RequestMapping(Missing.PATH) class C { @GetMapping("/x") void x() {} }
                """);
        CodeGraph g = graphOf(tmp.resolve("state"), repo);
        assertEquals("/${Missing.PATH}/x", g.endpoints().get(0).path());
    }

    @Test
    void sameSimpleNameInTwoReposResolvesWithinTheCallersOwnRepo(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("svc-a");
        Path b = tmp.resolve("svc-b");
        for (Path r : List.of(a, b)) {
            Files.createDirectories(r);
            Files.writeString(r.resolve("Client.java"), "@Component class Client { void go() {} }");
            Files.writeString(r.resolve("Caller.java"), "@Service class Caller { private final Client c; Caller(Client c) { this.c = c; } void run() { c.go(); } }");
        }
        CodeGraph g = graphOf(tmp.resolve("state"), a, b);
        for (TypeNode caller : g.bySimpleName("Caller")) {
            TypeNode target = g.resolve("Client", caller);
            assertEquals(caller.file().root(), target.file().root());
        }
    }

    @Test
    void anAmbiguousNameResolvesToNothingRatherThanAGuess(@TempDir Path tmp) throws Exception {
        Path r = tmp.resolve("r");
        Files.createDirectories(r.resolve("x"));
        Files.createDirectories(r.resolve("y"));
        Files.createDirectories(r.resolve("z"));
        Files.writeString(r.resolve("x/Dup.java"), "package x; class Dup {}");
        Files.writeString(r.resolve("y/Dup.java"), "package y; class Dup {}");
        Files.writeString(r.resolve("z/User.java"), "package z; class User { Dup d; }");
        CodeGraph g = graphOf(tmp.resolve("state"), r);
        assertNull(g.resolve("Dup", type(g, "User")));
        assertSame(g.bySimpleName("Dup").stream().filter(n -> n.fqn().equals("x.Dup")).findFirst().orElseThrow(),
                g.resolve("x.Dup", type(g, "User")));
    }

    @Test
    void joinPathsNormalizesSlashes() {
        assertEquals("/api/orders", CodeGraph.joinPaths("api/", "/orders/"));
        assertEquals("/", CodeGraph.joinPaths("", ""));
        assertEquals("/a/b", CodeGraph.joinPaths("/a//", "b"));
    }
}
