package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import dev.agentideate.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow 2 through the tools: submit, show, approve, with a client that can or cannot show forms. */
class PlanToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String IMPL = "order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java";
    static final String TOTAL_LINE = "        order.setTotal(total(price.amount(), request.quantity()));";

    /** A client whose developer answers each form with the next scripted answer. */
    static final class ScriptedClient implements ClientChannel {
        final List<JsonNode> answers = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<JsonNode> schemas = new ArrayList<>();
        boolean forms = true;

        @Override
        public boolean supportsElicitation() {
            return forms;
        }

        @Override
        public JsonNode elicit(String message, ObjectNode requestedSchema, Duration timeout) {
            messages.add(message);
            schemas.add(requestedSchema);
            return answers.isEmpty() ? null : answers.remove(0);
        }
    }

    @TempDir
    Path tmp;

    ToolRegistry registry;
    ScriptedClient client = new ScriptedClient();

    @BeforeEach
    void setUp() throws Exception {
        Path repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        registry = ToolRegistry.standard(new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null));
        registry.client(client);
    }

    private ToolRegistry.Outcome call(String tool, JsonNode args) {
        return registry.call(tool, args);
    }

    private String submitValid() {
        ObjectNode p = JSON.createObjectNode();
        p.put("title", "Discount").put("understanding", "Apply 10% off the total.");
        ObjectNode c = p.putArray("changes").addObject();
        c.put("kind", "edit").put("file", IMPL).put("before", TOTAL_LINE)
                .put("after", "        order.setTotal(total(price.amount(), request.quantity()).multiply(new java.math.BigDecimal(\"0.9\")));")
                .put("reason", "apply the discount");
        c.putArray("evidence").add("OrderServiceImpl#placeOrder");
        ToolRegistry.Outcome o = call("plan_submit", p);
        assertFalse(o.error(), o.text());
        return o.text();
    }

    private static ObjectNode answer(String action, String decision, String feedback) {
        ObjectNode a = JSON.createObjectNode().put("action", action);
        if (decision != null) {
            ObjectNode content = a.putObject("content").put("decision", decision);
            if (feedback != null) {
                content.put("feedback", feedback);
            }
        }
        return a;
    }

    private JsonNode plan(String id) {
        return JSON.createObjectNode().put("plan", id);
    }

    @Test
    void submitRendersTheReviewAndSaysWhatComesNext() {
        String t = submitValid();
        assertTrue(t.startsWith("Plan p1 v1: Discount   [draft · 1 change · valid]"), t);
        assertTrue(t.contains("  c1  edit  " + IMPL + " lines 38-38"), t);
        assertTrue(t.contains("      -   38 | " + TOTAL_LINE), t);
        assertTrue(t.contains("Next: present this plan to the developer"), t);
    }

    @Test
    void theDeveloperApprovesThroughTheChatForm() {
        submitValid();
        client.answers.add(answer("accept", "approve", null));
        ToolRegistry.Outcome o = call("plan_approve", plan("p1"));
        assertFalse(o.error(), o.text());
        assertTrue(o.text().startsWith("Approved by the developer in the chat: p1 v1."), o.text());
        assertTrue(client.messages.get(0).startsWith("Approve plan p1 v1 \"Discount\"? 1 change in OrderServiceImpl.java"), client.messages.get(0));
        JsonNode schema = client.schemas.get(0);
        assertEquals("approve", schema.at("/properties/decision/enum/0").asText());
        assertEquals("Approve v1", schema.at("/properties/decision/enumNames/0").asText());
        assertTrue(call("plan_show", plan("p1")).text().contains("[APPROVED (chat form)"));
        assertTrue(call("plan_approve", plan("p1")).text().contains("already approved (chat form)"));
    }

    @Test
    void requestedChangesGoBackToTheAgentAndIntoTheNextVersion() {
        submitValid();
        client.answers.add(answer("accept", "changes", "make it 5%, not 10%"));
        String t = call("plan_approve", plan("p1")).text();
        assertTrue(t.startsWith("The developer asked for changes to p1 v1: make it 5%, not 10%"), t);
        assertTrue(call("plan_show", JSON.createObjectNode()).text().contains("changes requested"));
        String v2 = call("plan_submit", JSON.createObjectNode().put("plan", "p1").put("understanding", "Apply 5% off.")).text();
        assertTrue(v2.contains("Feedback this version answers: make it 5%, not 10%"), v2);
    }

    @Test
    void declinedOrUnansweredFormsLeaveADraft() {
        submitValid();
        client.answers.add(answer("decline", null, null));
        assertTrue(call("plan_approve", plan("p1")).text().contains("declined to approve p1 v1"));
        String none = call("plan_approve", plan("p1")).text();
        assertTrue(none.contains("No decision came from the chat"), none);
        assertTrue(call("plan_show", plan("p1")).text().contains("[draft ·"));
    }

    @Test
    void withoutFormsCopilotsToolConfirmationIsTheGateAndIsRecordedAsSuch() {
        client.forms = false;
        submitValid();
        String t = call("plan_approve", plan("p1")).text();
        assertTrue(t.startsWith("Approved: p1 v1, through Copilot's confirmation of this tool call"), t);
        assertTrue(call("plan_show", JSON.createObjectNode()).text().contains("approved v1 (Copilot tool confirmation)"));
        boolean approveIsNotReadOnly = false;
        for (JsonNode tool : registry.toolsList().get("tools")) {
            if (tool.get("name").asText().equals("plan_approve")) {
                approveIsNotReadOnly = !tool.at("/annotations/readOnlyHint").asBoolean();
            }
        }
        assertTrue(approveIsNotReadOnly, "so Copilot asks the developer before running it");
    }

    @Test
    void inTerminalModeTheChatCannotApprove() {
        registry.approvalMode("terminal");
        submitValid();
        client.answers.add(answer("accept", "approve", null));
        String t = call("plan_approve", plan("p1")).text();
        assertTrue(t.contains("java -jar agentideate.jar approve p1 1"), t);
        assertTrue(client.messages.isEmpty(), "no form is shown");
        assertTrue(call("plan_show", plan("p1")).text().contains("[draft ·"));
    }

    @Test
    void anInvalidPlanIsNeverApproved() {
        ObjectNode p = JSON.createObjectNode().put("title", "Bad").put("understanding", "x");
        p.putArray("changes").addObject().put("kind", "edit").put("file", IMPL).put("before", "no such text").put("after", "y");
        assertTrue(call("plan_submit", p).text().contains("Do not show an invalid plan as ready"));
        client.answers.add(answer("accept", "approve", null));
        ToolRegistry.Outcome o = call("plan_approve", plan("p1"));
        assertTrue(o.error());
        assertTrue(o.text().startsWith("Not approvable: v1 has 1 problem"), o.text());
        assertTrue(client.messages.isEmpty(), "the developer is not even asked");
    }

    @Test
    void showListsPlansAndOlderVersions() {
        assertTrue(call("plan_show", JSON.createObjectNode()).text().startsWith("No plans yet."));
        submitValid();
        call("plan_submit", JSON.createObjectNode().put("plan", "p1").put("understanding", "v2 wording"));
        String list = call("plan_show", JSON.createObjectNode()).text();
        assertTrue(list.contains("  p1  v2  draft  1 changes  Discount"), list);
        String v1 = call("plan_show", JSON.createObjectNode().put("plan", "p1").put("version", 1)).text();
        assertTrue(v1.startsWith("Plan p1 v1") && v1.contains("(an older version; the latest is v2)"), v1);
        String v2 = call("plan_show", plan("p1")).text();
        assertTrue(v2.contains("      -   38 |"), "plan_show shows every change in full: " + v2);
        assertTrue(call("plan_show", plan("p9")).error());
    }
}
