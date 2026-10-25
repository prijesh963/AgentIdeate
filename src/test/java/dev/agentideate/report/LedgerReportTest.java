package dev.agentideate.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerReportTest {

    @TempDir
    Path home;

    private Path workspace(String name, String... rows) throws Exception {
        Path dir = home.resolve("workspaces").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ledger.jsonl"), String.join("\n", rows) + "\n");
        return dir;
    }

    private static String row(String ts, String tool, int returned, int referenced, int omitted, String thread, boolean continued) {
        return "{\"ts\":\"" + ts + "\",\"tool\":\"" + tool + "\",\"ms\":5,\"returnedBytes\":" + returned
                + ",\"referencedFiles\":2,\"referencedBytes\":" + referenced + ",\"thread\":" + (thread == null ? "null" : "\"" + thread + "\"")
                + ",\"continued\":" + continued + ",\"omittedBytes\":" + omitted + "}";
    }

    @Test
    void totalsPerToolAndWhatThreadsSaved() throws Exception {
        Path ws = workspace("shop-1",
                row("2026-10-20T10:00:00Z", "endpoint_flow", 2000, 8000, 0, "t1", false),
                row("2026-10-20T10:01:00Z", "impact_of", 400, 4000, 800, "t1", true),
                row("2026-10-20T10:02:00Z", "repo_overview", 2400, 6000, 0, null, false),
                "{not json",
                // an M1-era row without thread fields still counts
                "{\"ts\":\"2026-10-20T10:03:00Z\",\"tool\":\"locate\",\"returnedBytes\":400,\"referencedBytes\":1200}");
        String r = LedgerReport.render(List.of(ws), Instant.parse("2026-10-19T00:00:00Z").toEpochMilli(), "shop");
        assertTrue(r.startsWith("agentideate usage for shop: 4 calls"), r);
        assertTrue(r.contains(" endpoint_flow         1        500         2.0k              -"), r);
        assertTrue(r.contains(" impact_of             1        100         1.0k            200"), r);
        assertTrue(r.contains("Answers: 1.3k tokens. Files they cite: 4.8k tokens. Not repeated in threads: 200 tokens."), r);
        // avoided = (19200 - 5200) + 800 = 14800 chars = 3.7k tokens, of 20000 chars stood in for = 74%
        assertTrue(r.contains("Estimated reading avoided: ~3.7k tokens (74% of what the answers stood in for)."), r);
        assertTrue(r.contains("Threads: 1 topics, 1 follow-up questions continued one."), r);
        assertTrue(r.contains("1 unreadable ledger lines skipped."), r);
    }

    @Test
    void oldRowsAreLeftOutAndAnEmptyPeriodSaysSo() throws Exception {
        Path ws = workspace("shop-1", row("2026-01-01T00:00:00Z", "locate", 100, 1000, 0, "t1", false));
        String r = LedgerReport.render(List.of(ws), Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), "shop, last 7 days");
        assertEquals("No agentideate calls recorded for shop, last 7 days.\n", r);
    }

    @Test
    void allWorkspacesAreFoundUnderTheHome() throws Exception {
        workspace("a-1", row("2026-10-20T10:00:00Z", "locate", 100, 1000, 0, "t1", false));
        workspace("b-2", row("2026-10-20T10:00:00Z", "locate", 100, 1000, 0, "t1", false));
        List<Path> all = LedgerReport.allWorkspaces(home);
        assertEquals(2, all.size());
        assertTrue(LedgerReport.render(all, 0, "all").startsWith("agentideate usage for all: 2 calls"));
        assertTrue(LedgerReport.allWorkspaces(home.resolve("nowhere")).isEmpty());
    }
}
