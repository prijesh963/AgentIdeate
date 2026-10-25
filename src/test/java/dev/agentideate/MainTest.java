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

    @Test
    void benchReportAndSetupFromTheTerminal() throws Exception {
        String repo = TestSupport.shop().toString();
        String tasks = TestSupport.shopTasks().toString();
        assertEquals(0, run("", "bench", tasks, "--repo", repo));
        assertTrue(out().contains("Facts found: 40/40."), out());
        assertEquals(0, run("", "report", "--days", "1", "--repo", repo));
        assertTrue(out().contains("agentideate usage for shop, last 1 days: 11 calls"), out());
        assertEquals(0, run("", "report", "--all"));
        assertEquals(2, run("", "report", "--days", "soon"));
        // run from classes, not the jar: setup reports that and exits 1, but still prints a usable entry
        assertEquals(1, run("", "setup"));
        assertTrue(out().contains("\"agentideate\""), out());
        assertTrue(out().contains("<path to agentideate.jar>"), out());
    }

    @Test
    void aBadTaskFileIsAMessageNotAStackTrace(@TempDir Path tmp) throws Exception {
        assertEquals(2, run("", "bench"));
        assertEquals(1, run("", "bench", tmp.resolve("missing.json").toString(), "--repo", TestSupport.shop().toString()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Cannot read tasks from"), err.toString(StandardCharsets.UTF_8));
    }
}
