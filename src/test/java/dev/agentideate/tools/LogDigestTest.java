package dev.agentideate.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogDigestTest {

    /** Classes "in the workspace" for these tests, by fully qualified name. */
    private static final Map<String, String> INDEX = Map.of(
            "com.shop.order.service.OrderServiceImpl", "order-service/.../OrderServiceImpl.java",
            "com.shop.order.service.OrderServiceImplTest", "order-service/.../OrderServiceImplTest.java",
            "com.shop.order.OrderServiceApplication", "order-service/.../OrderServiceApplication.java");

    private final LogDigest digest = new LogDigest(classLine -> {
        int colon = classLine.lastIndexOf(':');
        String path = INDEX.get(classLine.substring(0, colon).replaceAll("\\$.*", ""));
        return path == null ? null : path + ":" + classLine.substring(colon + 1);
    }, abs -> abs.replaceFirst("^/work/shop/", ""));

    static String fixture(String name) throws IOException {
        try (InputStream in = LogDigestTest.class.getResourceAsStream("/logs/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String run(String name) throws IOException {
        return String.join("\n", digest.digest(fixture(name)));
    }

    @Test
    void gradleTestFailuresWithProjectFramesOnly() throws IOException {
        String d = run("gradle-test-failure.log");
        assertTrue(d.startsWith("Build: FAILED\nWhat went wrong (Gradle):\n  Execution failed for task ':order-service:test'."), d);
        assertTrue(d.contains("Test failures (2): 5 tests completed, 2 failed"), d);
        assertTrue(d.contains("  OrderServiceImplTest > placeOrderPublishesEvent()  org.opentest4j.AssertionFailedError: expected: <CREATED> but was: <null>"
                + "  at OrderServiceImplTest.placeOrderPublishesEvent order-service/.../OrderServiceImplTest.java:41"), d);
        assertTrue(d.contains("at OrderServiceImpl.lambda$cancelOrder$0 order-service/.../OrderServiceImpl.java:48 < OrderServiceImpl.cancelOrder"), d);
        assertFalse(d.contains("AssertionFailureBuilder"), "library frames are not shown: " + d);
        assertFalse(d.contains("Exceptions ("), "a test's trace is reported once, under the test: " + d);
    }

    @Test
    void gradleCompileErrorsWithSymbolAndLocation() throws IOException {
        String d = run("gradle-compile-failure.log");
        assertTrue(d.contains("Compile errors (2):"), d);
        assertTrue(d.contains("  order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:38  cannot find symbol; "
                + "symbol: method applyDiscount(BigDecimal); location: class OrderServiceImpl"), d);
        assertFalse(d.contains("BUILD FAILED in"), d);
    }

    @Test
    void mavenSurefireFailures() throws IOException {
        String d = run("maven-test-failure.log");
        assertEquals("Build: FAILED\nTest failures (1): Tests run: 3, Failures: 1, Errors: 0, Skipped: 0\n"
                + "  InventoryServiceTest.reserveDecrementsStock  expected: <4> but was: <5>", d);
    }

    @Test
    void springBootStartupFailureWithRootCause() throws IOException {
        String d = run("spring-boot-startup.log");
        assertTrue(d.startsWith("Spring Boot: APPLICATION FAILED TO START\n  Description:\n  Parameter 2 of constructor"), d);
        assertTrue(d.contains("  Action:\n  Consider defining a bean of type 'com.shop.order.client.PricingClient' in your configuration.\nExceptions"), d);
        assertTrue(d.contains("    root cause: NoSuchBeanDefinitionException: No qualifying bean of type 'com.shop.order.client.PricingClient' available"), d);
        assertTrue(d.contains("    at OrderServiceApplication.main order-service/.../OrderServiceApplication.java:12  (+4 library frames)"), d);
    }

    @Test
    void mavenCompileErrorsAndRepeatedTracesAreCounted() {
        String log = """
                [ERROR] /work/shop/a/src/main/java/A.java:[10,5] cannot find symbol
                [ERROR]   symbol:   variable x
                [ERROR] /work/shop/a/src/main/java/A.java:[10,5] cannot find symbol
                [ERROR]   symbol:   variable x
                java.lang.IllegalStateException: boom
                \tat com.shop.order.service.OrderServiceImpl.cancelOrder(OrderServiceImpl.java:47)
                java.lang.IllegalStateException: boom
                \tat com.shop.order.service.OrderServiceImpl.cancelOrder(OrderServiceImpl.java:47)
                """;
        String d = String.join("\n", digest.digest(log));
        assertTrue(d.contains("  a/src/main/java/A.java:10  cannot find symbol; symbol: variable x  (x2)"), d);
        assertTrue(d.contains("  IllegalStateException: boom  (x2)"), d);
    }

    @Test
    void ansiColoursAndTimestampsAreIgnored() {
        assertEquals("BUILD FAILED", LogDigest.strip("\u001B[31mBUILD FAILED\u001B[0m"));
        assertEquals("ERROR boom", LogDigest.strip("2026-10-25 18:01:02.123 ERROR boom"));
    }

    @Test
    void nothingRecognizedIsSaid() {
        List<String> d = digest.digest("hello\nworld\n");
        assertEquals(List.of("Nothing recognized: no build outcome, compile error, test failure or exception in 2 lines."), d);
    }
}
