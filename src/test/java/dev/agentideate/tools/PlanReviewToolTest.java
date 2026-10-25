package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import dev.agentideate.exec.CommandRunner;
import dev.agentideate.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow 4: the computed conformance of a run to its approved plan, the review pack, and checked findings. */
class PlanReviewToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String IMPL = "order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java";
    static final String TOTAL = "        order.setTotal(total(price.amount(), request.quantity()));";
    static final String NEW_TOTAL = "        order.setTotal(total(price.amount(), request.quantity()).negate());";

    @TempDir
    Path tmp;

    Path repo;
    ToolRegistry registry;
    PlanToolsTest.ScriptedClient client = new PlanToolsTest.ScriptedClient();
    /** What {@code git status --porcelain} prints; the root is a git work tree. */
    final AtomicReference<String> gitStatus = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        registry = ToolRegistry.standard(new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null));
        registry.client(client);
        registry.runner((cmd, dir, timeout) -> {
            if (!cmd.get(0).equals("git")) {
                return new CommandRunner.Result(0, "BUILD SUCCESSFUL", false, 1);
            }
            if (cmd.contains("--is-inside-work-tree")) {
                return new CommandRunner.Result(0, "true\n", false, 1);
            }
            if (cmd.contains("--show-prefix")) {
                return new CommandRunner.Result(0, "\n", false, 1);
            }
            return new CommandRunner.Result(0, gitStatus.get(), false, 1);
        });
        ObjectNode p = JSON.createObjectNode().put("title", "Negate").put("understanding", "Negate the total.");
        p.putArray("changes").addObject().put("kind", "edit").put("file", IMPL).put("before", TOTAL).put("after", NEW_TOTAL);
        assertFalse(registry.call("plan_submit", p).error());
    }

    private static ObjectNode answer(String decision) {
        ObjectNode a = JSON.createObjectNode().put("action", "accept");
        a.putObject("content").put("decision", decision);
        return a;
    }

    private static ObjectNode plan() {
        return JSON.createObjectNode().put("plan", "p1");
    }

    /** Approves and implements p1; git then shows the plan's file as modified, as it would. */
    private void implement() {
        gitStatus.set(" M README.md\n"); // the developer's own change, from before the run
        client.answers.add(answer("approve"));
        client.answers.add(answer("start"));
        assertFalse(registry.call("plan_approve", plan()).error());
        String t = registry.call("plan_implement", plan()).text();
        assertTrue(t.startsWith("Implemented p1 v1 (run r1)"), t);
        gitStatus.set(" M README.md\n M " + IMPL + "\n");
    }

    @Test
    void aPlanNotYetImplementedHasNothingToReview() {
        ToolRegistry.Outcome o = registry.call("plan_review", plan());
        assertTrue(o.error());
        assertTrue(o.text().contains("p1 has no applied implementation to review (it is draft)"), o.text());
    }

    @Test
    void anUntouchedRunConformsAndChangesMadeBeforeItAreNotCountedAgainstIt() {
        implement();
        String t = registry.call("plan_review", plan()).text();
        assertTrue(t.startsWith("Review of p1 v1 \"Negate\" (run r1, started via chat form; compile passed)"), t);
        assertTrue(t.contains("Verdict: CONFORMS: every approved change is in place, unchanged since the run, and git shows nothing else changed."), t);
        assertTrue(t.contains("  ok      c1 edit " + IMPL + "\n"), t);
        assertTrue(t.contains("Not in the plan: nothing (git)."), t);
    }

    @Test
    void anEditAfterTheRunIsDriftAndSaysWhatWasLost() throws Exception {
        implement();
        Path impl = repo.resolve(IMPL);
        Files.writeString(impl, Files.readString(impl).replace(NEW_TOTAL, TOTAL));
        String t = registry.call("plan_review", plan()).text();
        assertTrue(t.contains("Verdict: DEVIATES"), t);
        assertTrue(t.contains("  CHANGED c1 edit " + IMPL + ": edited since the run; c1: new text no longer there; the old text is back (line 38)"), t);
    }

    @Test
    void aFileChangedOutsideThePlanIsReported() {
        implement();
        String extra = "order-service/src/main/java/com/shop/order/web/OrderController.java";
        gitStatus.set(" M README.md\n M " + IMPL + "\n M " + extra + "\n?? notes.txt\n");
        String t = registry.call("plan_review", plan()).text();
        assertTrue(t.contains("Verdict: DEVIATES"), t);
        assertTrue(t.contains("Not in the plan: changed since the run started (yours, or the agent's outside the plan): " + extra + ", notes.txt"), t);
        assertFalse(t.contains("README.md"), "changed before the run, so not the run's");
    }

    @Test
    void aWrittenFileThatIsGoneMakesItIncomplete() throws Exception {
        implement();
        Files.delete(repo.resolve(IMPL));
        String t = registry.call("plan_review", plan()).text();
        assertTrue(t.contains("Verdict: INCOMPLETE"), t);
        assertTrue(t.contains("  MISSING c1 edit " + IMPL + ": the file is gone"), t);
    }

    @Test
    void withoutGitTheCheckIsSaidToBeSkippedNotPassed() throws Exception {
        registry.runner((cmd, dir, timeout) -> cmd.get(0).equals("git")
                ? new CommandRunner.Result(128, "not a repo", false, 1)
                : new CommandRunner.Result(0, "BUILD SUCCESSFUL", false, 1));
        client.answers.add(answer("approve"));
        client.answers.add(answer("start"));
        registry.call("plan_approve", plan());
        registry.call("plan_implement", plan());
        String t = registry.call("plan_review", plan()).text();
        assertTrue(t.contains("Verdict: CONFORMS: every approved change is in place, unchanged since the run; git was not checked (below)."), t);
        assertTrue(t.contains("Not in the plan: not checked (shop: not a git work tree, or git unavailable at the run)"), t);
    }

    @Test
    void thePackShowsTheCodeAsWrittenItsMethodCallersAndTests() {
        implement();
        String t = registry.call("plan_review", plan()).text();
        assertTrue(t.contains("  c1 " + IMPL + ":38\n      38 | " + NEW_TOTAL + "\n"), t);
        assertTrue(t.contains("  OrderServiceImpl.placeOrder  " + IMPL + ":30 (c1)\n"), t);
        assertTrue(t.contains("    called by OrderController.placeOrder  order-service/src/main/java/com/shop/order/web/OrderController.java:34\n"), t);
        assertTrue(t.contains("    tests: OrderServiceImplTest order-service/src/test/java/com/shop/order/service/OrderServiceImplTest.java"), t);
        assertTrue(t.contains("Record what you find: plan_review plan=p1 findings=[{file, line, text, severity}]."), t);
    }

    @Test
    void findingsAreCheckedAgainstRealFilesAndLinesBeforeTheyAreRecorded() throws Exception {
        implement();
        ObjectNode args = plan();
        var f = args.putArray("findings");
        f.addObject().put("file", IMPL).put("line", 38).put("severity", "major").put("text", "negating makes totals negative");
        f.addObject().put("file", "OrderServiceImpl.java").put("line", 40).put("text", "no null check on price");
        f.addObject().put("file", IMPL).put("line", 900).put("text", "invented line");
        f.addObject().put("file", "order-service/src/main/java/Nope.java").put("line", 1).put("text", "invented file");
        f.addObject().put("file", "application.yml").put("line", 1).put("text", "which one?");
        f.addObject().put("file", IMPL).put("line", 38).put("severity", "urgent").put("text", "bad severity");
        String t = registry.call("plan_review", args).text();
        assertTrue(t.contains("Recorded 2 findings:\n  major  " + IMPL + ":38  negating makes totals negative\n  minor  " + IMPL + ":40  no null check on price\n"), t);
        assertTrue(t.contains("  " + IMPL + ":900  line 900 is not in " + IMPL + " ("), t);
        assertTrue(t.contains("  order-service/src/main/java/Nope.java:1  no such file in the project"), t);
        assertTrue(t.matches("(?s).*  application.yml:1  \\d+ files are named application.yml; give its path.*"), t);
        assertTrue(t.contains("  " + IMPL + ":38  severity must be one of"), t);

        Path state = stateDir();
        JsonNode stored = JSON.readTree(state.resolve("reviews/p1.json").toFile());
        assertEquals(1, stored.size());
        assertEquals("CONFORMS", stored.get(0).path("verdict").asText());
        assertEquals(2, stored.get(0).path("findings").size());
        assertEquals(4, stored.get(0).path("rejected").size());
        assertTrue(Files.readString(state.resolve("audit.jsonl")).contains("\"event\":\"reviewed\""));
    }

    private Path stateDir() throws Exception {
        return new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null).snapshot(null).stateDir();
    }
}
