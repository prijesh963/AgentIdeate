package dev.agentideate.graph;

import dev.agentideate.TestSupport;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.graph.ReverseIndex.EntryPoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverseIndexTest {

    static CodeGraph graph;

    @BeforeAll
    static void build(@TempDir Path state) throws Exception {
        graph = CodeGraphTest.graphOf(state, TestSupport.shop());
    }

    @Test
    void callersIncludeCallsWrittenAgainstTheInterface() {
        TypeNode impl = CodeGraphTest.type(graph, "OrderServiceImpl");
        TypeNode iface = CodeGraphTest.type(graph, "OrderService");
        assertEquals(List.of("OrderController#placeOrder"), graph.reverse().callersOf(impl, "placeOrder").stream()
                .map(r -> r.owner().name() + "#" + r.method().name()).toList());
        assertEquals(List.of("OrderController#placeOrder"), graph.reverse().callersOf(iface, "placeOrder").stream()
                .map(r -> r.owner().name() + "#" + r.method().name()).toList());
    }

    @Test
    void injectorsOfAnImplementationIncludeInjectionsOfItsInterface() {
        TypeNode impl = CodeGraphTest.type(graph, "OrderServiceImpl");
        assertEquals(List.of("OrderController"), graph.reverse().injectorsOf(impl).stream().map(i -> i.owner().name()).toList());
    }

    @Test
    void entryPointsWalkCallersUpToRoutesAndConsumers() {
        TypeNode stock = CodeGraphTest.type(graph, "InventoryService");
        List<EntryPoint> eps = graph.reverse().entryPoints(stock, "reserve");
        List<String> got = eps.stream().map(e -> e.route() != null
                ? "route " + e.route().httpMethod() + " " + e.route().path()
                : e.consumer().system() + " " + e.consumer().channel()).sorted().toList();
        assertEquals(List.of("kafka order.created", "route POST /api/stock/{sku}/reserve"), got);
    }

    @Test
    void testsAreFoundByReferenceAndByConvention() {
        assertEquals(List.of("OrderServiceImplTest"),
                graph.reverse().testsOf(CodeGraphTest.type(graph, "OrderServiceImpl")).stream().map(TypeNode::name).toList());
        assertEquals(List.of("OrderServiceImplTest"),
                graph.reverse().testsOf(CodeGraphTest.type(graph, "OrderRepository")).stream().map(TypeNode::name).toList(),
                "the test mocks the repository");
        assertTrue(graph.reverse().testsOf(CodeGraphTest.type(graph, "PriceService")).isEmpty());
    }
}
