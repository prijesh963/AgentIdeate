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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow 3 through the tools: the Start gate, the write, the report, the undo. */
class PlanImplementToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String IMPL = "order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java";
    static final String TOTAL = "        order.setTotal(total(price.amount(), request.quantity()));";
    static final String NEW_TOTAL = "        order.setTotal(total(price.amount(), request.quantity()).negate());";

    @TempDir
    Path tmp;

    Path repo;
    ToolRegistry registry;
    PlanToolsTest.ScriptedClient client = new PlanToolsTest.ScriptedClient();

    @BeforeEach
    void setUp() throws Exception {
        repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        registry = ToolRegistry.standard(new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null));
        registry.client(client);
        registry.runner((cmd, dir, timeout) -> cmd.get(0).equals("git")
                ? new CommandRunner.Result(128, "not a repo", false, 1)
                : new CommandRunner.Result(0, "BUILD SUCCESSFUL", false, 1));
        ObjectNode p = JSON.createObjectNode().put("title", "Negate").put("understanding", "Negate the total.");
        p.putArray("changes").addObject().put("kind", "edit").put("file", IMPL).put("before", TOTAL).put("after", NEW_TOTAL);
        assertFalse(registry.call("plan_submit", p).error());
    }

    private static ObjectNode answer(String decision) {
        ObjectNode a = JSON.createObjectNode().put("action", "accept");
        a.putObject("content").put("decision", decision);
        return a;
    }

    private JsonNode plan() {
        return JSON.createObjectNode().put("plan", "p1");
    }

    private void approve() {
        client.answers.add(answer("approve"));
        assertTrue(registry.call("plan_approve", plan()).text().startsWith("Approved by the developer in the chat"));
    }

    @Test
    void anUnapprovedPlanIsRefusedBeforeAnyoneIsAsked() {
        ToolRegistry.Outcome o = registry.call("plan_implement", plan());
        assertTrue(o.error());
        assertTrue(o.text().contains("has no approved latest version"), o.text());
        assertTrue(client.messages.isEmpty());
    }

    @Test
    void theDeveloperStartsItInTheChatAndTheReportSaysWhatHappened() throws Exception {
        approve();
        client.answers.add(answer("start"));
        String t = registry.call("plan_implement", plan()).text();
        assertTrue(client.messages.get(1).startsWith("Start implementing p1 v1 \"Negate\"? This writes: edit OrderServiceImpl.java."),
                client.messages.get(1));
        assertEquals("Start: write 1 file", client.schemas.get(1).at("/properties/decision/enumNames/0").asText());
        assertTrue(t.startsWith("Implemented p1 v1 (run r1), started via chat form."), t);
        assertTrue(t.contains("  ok   c1 edited " + IMPL + " from line 38"), t);
        assertTrue(t.contains("Compile: passed"), t);
        assertTrue(t.contains("Undo with plan_rollback (run r1)"), t);
        assertTrue(Files.readString(repo.resolve(IMPL)).contains(NEW_TOTAL));
        assertTrue(registry.call("plan_show", plan()).text().contains("[IMPLEMENTED (run r1)"));
    }

    @Test
    void notNowWritesNothing() throws Exception {
        approve();
        client.answers.add(answer("wait"));
        String t = registry.call("plan_implement", plan()).text();
        assertTrue(t.startsWith("Not started"), t);
        assertTrue(Files.readString(repo.resolve(IMPL)).contains(TOTAL));
    }

    @Test
    void withoutFormsTheToolConfirmationStartsItAndTerminalModeRefuses() throws Exception {
        approve();
        registry.approvalMode("terminal");
        String t = registry.call("plan_implement", plan()).text();
        assertTrue(t.contains("java -jar agentideate.jar implement p1"), t);
        assertTrue(Files.readString(repo.resolve(IMPL)).contains(TOTAL));

        registry.approvalMode("chat");
        client.forms = false;
        String started = registry.call("plan_implement", JSON.createObjectNode().put("plan", "p1").put("compile", false)).text();
        assertTrue(started.startsWith("Implemented p1 v1 (run r1), started via Copilot tool confirmation."), started);
        assertTrue(started.contains("Compile: skipped"), started);
    }

    @Test
    void rollbackThroughTheTool() throws Exception {
        approve();
        client.answers.add(answer("start"));
        registry.call("plan_implement", plan());
        String t = registry.call("plan_rollback", JSON.createObjectNode().put("run", "p1")).text();
        assertTrue(t.startsWith("Undid run r1 (p1 v1): 1 file restored."), t);
        assertTrue(Files.readString(repo.resolve(IMPL)).contains(TOTAL));
        assertTrue(registry.call("plan_rollback", JSON.createObjectNode().put("run", "r1")).text().contains("already rolled back"));
        assertTrue(registry.call("plan_rollback", JSON.createObjectNode().put("run", "r9")).error());
    }
}
