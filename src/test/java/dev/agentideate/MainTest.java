package dev.agentideate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    Path home;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String stdin, String... argv) throws Exception {
        return Main.run(argv, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new AppHome(home));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpAndVersion() throws Exception {
        assertEquals(0, run("", "help"));
        assertTrue(out().contains("java -jar agentideate.jar overview"));
        assertEquals(0, run("", "--version"));
    }

    @Test
    void overviewLocateAndFlowFromTheTerminal() throws Exception {
        String repo = TestSupport.shop().toString();
        assertEquals(0, run("", "overview", "--repo", repo));
        assertTrue(out().contains("4 Spring Boot services"));
        assertEquals(0, run("", "locate", "stock", "repository", "--repo=" + repo));
        assertTrue(out().contains("StockRepository [spring-data-repository]"));
        assertEquals(0, run("", "flow", "/api/stock/{sku}", "GET", "-r", repo));
        assertTrue(out().contains("InventoryService#stockFor"));
    }

    @Test
    void badUsageExitsWithTwoAndAToolErrorWithOne() throws Exception {
        assertEquals(2, run("", "frobnicate"));
        assertEquals(2, run("", "locate"));
        assertEquals(2, run("", "--repo"));
        assertEquals(1, run("", "overview", "--repo", home.resolve("missing").toString()));
    }

    @Test
    void withNoCommandItServesMcpOnStdio() throws Exception {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":{}}}\n";
        assertEquals(0, run(init));
        assertTrue(out().contains("\"serverInfo\""), out());
        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty() || !out().contains("agentideate:"),
                "diagnostics never go to stdout");
    }
}
