package dev.agentideate.bench;

import dev.agentideate.TestSupport;
import dev.agentideate.tools.ToolRegistry;
import dev.agentideate.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkTest {

    @TempDir
    Path home;

    private List<Benchmark.Result> runShop() throws IOException {
        ToolRegistry registry = TestSupport.registry(home, TestSupport.shop());
        return Benchmark.run(registry, Workspace.of(List.of(TestSupport.shop())), Benchmark.load(TestSupport.shopTasks()));
    }

    @Test
    void everyShopTaskIsAnsweredWithAllItsFacts() throws IOException {
        List<Benchmark.Result> results = runShop();
        assertEquals(11, results.size());
        for (Benchmark.Result r : results) {
            assertTrue(r.passed(), r.task().id() + ": missing " + r.missingFacts() + ", files " + r.missingFiles()
                    + (r.error() ? ", error " + r.errorText() : ""));
        }
    }

    @Test
    void answersStayWellBelowTheFilesTheyReplace() throws IOException {
        long answers = 0;
        long files = 0;
        for (Benchmark.Result r : runShop()) {
            if (r.fileChars() > 0) {
                answers += r.answerChars();
                files += r.fileChars();
            }
        }
        // A regression guard, not a claim: the sample's files are small, real ones are larger.
        assertTrue(answers < files / 2, "answers " + answers + " chars vs files " + files);
    }

    @Test
    void theReportShowsTotalsCallsAndTheFixedCost() throws IOException {
        String report = Benchmark.render(runShop(), "shop", 5_200);
        assertTrue(report.startsWith("agentideate benchmark: 11 tasks on shop"), report);
        assertTrue(report.contains("Facts found: 40/40."), report);
        assertTrue(report.contains("Tool calls: 10 (one per task) instead of at least 65 file reads."), report);
        assertTrue(report.contains("tool definitions add ~1.3k tokens to every agent request"), report);
        assertTrue(report.contains("the least an agent without agentideate must read"), report);
    }

    @Test
    void aMissingFactOrFileFailsTheTaskAndIsNamed(@TempDir Path tmp) throws IOException {
        Path tasks = tmp.resolve("t.json");
        Files.writeString(tasks, """
                {"tasks": [
                  {"id": "bad-fact", "tool": "locate", "args": {"query": "PriceService"}, "expect": ["no such text"], "files": []},
                  {"id": "bad-file", "tool": "locate", "args": {"query": "PriceService"}, "expect": [], "files": ["nope/Missing.java", "../../etc/passwd"]},
                  {"id": "bad-tool", "tool": "frobnicate", "args": {}}
                ]}""");
        ToolRegistry registry = TestSupport.registry(home, TestSupport.shop());
        List<Benchmark.Result> results = Benchmark.run(registry, Workspace.of(List.of(TestSupport.shop())), Benchmark.load(tasks));
        assertFalse(results.get(0).passed());
        assertEquals(List.of("nope/Missing.java", "../../etc/passwd"), results.get(1).missingFiles(), "files outside the workspace do not count");
        assertTrue(results.get(2).error());
        String report = Benchmark.render(results, "shop", 100);
        assertTrue(report.contains("MISSING in bad-fact: expected \"no such text\""), report);
        assertTrue(report.contains("NO SUCH FILE in bad-file: nope/Missing.java"), report);
        assertTrue(report.contains("ERROR in bad-tool: Unknown tool 'frobnicate'"), report);
    }

    @Test
    void aTaskFileWithoutTasksOrToolsIsRejected(@TempDir Path tmp) throws IOException {
        Path empty = tmp.resolve("e.json");
        Files.writeString(empty, "{\"tasks\": []}");
        assertThrows(IOException.class, () -> Benchmark.load(empty));
        Path noTool = tmp.resolve("n.json");
        Files.writeString(noTool, "{\"tasks\": [{\"id\": \"x\"}]}");
        assertThrows(IOException.class, () -> Benchmark.load(noTool));
    }
}
