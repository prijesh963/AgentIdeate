package dev.agentideate.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentideate.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressLogToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    Path repo;
    ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        registry = TestSupport.registry(tmp.resolve("home"), repo);
    }

    private ToolRegistry.Outcome call(String... kv) {
        var args = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return registry.call("compress_log", args);
    }

    @Test
    void aLogFileInsideTheProjectIsDigestedWithFramesMappedToTheIndex() throws Exception {
        Files.writeString(repo.resolve("order-service/test.log"), LogDigestTest.fixture("gradle-test-failure.log"));
        ToolRegistry.Outcome o = call("path", "order-service/test.log");
        assertFalse(o.error(), o.text());
        assertTrue(o.text().contains("at OrderServiceImplTest.placeOrderPublishesEvent order-service/src/test/java/com/shop/order/service/OrderServiceImplTest.java:41"), o.text());
        assertTrue(o.text().lines().reduce((a, b) -> b).orElseThrow().contains("from a 1.3 KB log"), o.text());
    }

    @Test
    void compileErrorsFromAnotherMachineMapToWorkspaceFiles() throws Exception {
        ToolRegistry.Outcome o = call("text", LogDigestTest.fixture("gradle-compile-failure.log"));
        assertTrue(o.text().contains("  order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:38  cannot find symbol"), o.text());
    }

    @Test
    void filesOutsideTheProjectAreRefused() throws Exception {
        Path outside = tmp.resolve("secret.log");
        Files.writeString(outside, "BUILD FAILED");
        ToolRegistry.Outcome o = call("path", outside.toString());
        assertTrue(o.error());
        assertTrue(o.text().contains("Only files inside the project are read"), o.text());
        assertTrue(call("path", "../secret.log").error(), "no escaping through ..");
    }

    @Test
    void withNoInputTheJUnitReportsAreRead() throws Exception {
        Path reports = repo.resolve("order-service/build/test-results/test");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-com.shop.order.service.OrderServiceImplTest.xml"),
                LogDigestTest.fixture("junit/TEST-com.shop.order.service.OrderServiceImplTest.xml"));
        String t = call().text();
        assertTrue(t.startsWith("JUnit reports: 1 files, 3 tests, 2 failed"), t);
        assertTrue(t.contains("  FAIL OrderServiceImplTest.placeOrderPublishesEvent()  expected: <CREATED> but was: <null>"
                + "  at OrderServiceImplTest.placeOrderPublishesEvent order-service/src/test/java/com/shop/order/service/OrderServiceImplTest.java:41"), t);
        assertTrue(t.contains("  ERROR OrderServiceImplTest.cancelUnknownOrder()  No order 99"
                + "  at OrderServiceImpl.cancelOrder order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:47"), t);
    }

    @Test
    void aReportWithADoctypeIsNotParsed() throws Exception {
        Path reports = repo.resolve("order-service/build/test-results/test");
        Files.createDirectories(reports);
        Files.writeString(tmp.resolve("leak.txt"), "TOP SECRET");
        Files.writeString(reports.resolve("TEST-evil.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE t [<!ENTITY x SYSTEM "file://%s">]>
                <testsuite><testcase classname="E" name="t"><failure message="&x;">&x;</failure></testcase></testsuite>
                """.formatted(tmp.resolve("leak.txt")));
        String t = call().text();
        assertFalse(t.contains("TOP SECRET"), t);
        assertTrue(t.contains("unreadable report TEST-evil.xml"), t);
    }

    @Test
    void noReportsSaysWhatToDo() {
        assertTrue(call().text().startsWith("No JUnit XML reports found"));
    }
}
