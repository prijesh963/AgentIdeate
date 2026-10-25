package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanChange;
import dev.agentideate.plan.PlanService;
import dev.agentideate.plan.PlanVersion;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Workflow 2, approving. The decision is the developer's, not the agent's:
 *
 * <ul>
 *   <li>when the client can show a form in the chat (MCP elicitation), the developer clicks Approve
 *       or Request changes there, and the answer comes to agentideate without passing the model;</li>
 *   <li>otherwise the tool relies on Copilot asking the developer to confirm the call (it is marked
 *       as not read-only), recorded as a weaker approval;</li>
 *   <li>in terminal mode the tool refuses and names the {@code agentideate approve} command.</li>
 * </ul>
 *
 * A version with problems against the current code is never approved.
 */
final class PlanApproveTool implements Tool {

    static final Duration FORM_TIMEOUT = Duration.ofMinutes(15);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolRegistry registry;

    PlanApproveTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "plan_approve";
    }

    @Override
    public String description() {
        return "Ask the developer to approve a plan version (they decide, in the chat). Call after presenting the plan.";
    }

    @Override
    public boolean threaded() {
        return false;
    }

    @Override
    public boolean readOnly() {
        return false; // so a client without forms still asks the developer before this runs
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("plan").put("type", "string");
        p.putObject("version").put("type", "integer").put("description", "Default: latest");
        s.putArray("required").add("plan");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        PlanService service = new PlanService(snapshot, registry.now());
        try {
            Plan plan = service.store().get(args.required("plan"));
            if (plan == null) {
                throw new ToolException("No plan '" + args.string("plan") + "'. plan_show lists the plans.");
            }
            int n = args.integer("version", plan.latest().number, 1, Integer.MAX_VALUE);
            PlanVersion v = plan.version(n);
            if (v == null) {
                throw new ToolException(plan.id + " has no v" + n + ".");
            }
            if (plan.approved() && plan.approvedVersion == n) {
                out.line(plan.id + " v" + n + " is already approved (" + plan.approvedVia + ").");
                return;
            }
            String blocker = service.approvalBlocker(plan, v);
            service.store().save(plan);
            if (blocker != null) {
                throw new ToolException("Not approvable: " + blocker);
            }
            if ("terminal".equals(registry.approvalMode())) {
                out.line("This team approves plans in the terminal, not in the chat. Ask the developer to run:");
                out.line("  java -jar agentideate.jar approve " + plan.id + " " + n);
                out.line("Then continue when they say it is approved (plan_show " + plan.id + " confirms it).");
                return;
            }
            ClientChannel client = registry.client();
            if (client != null && client.supportsElicitation()) {
                askInChat(service, plan, v, client, out);
                return;
            }
            service.approve(plan, v, "Copilot tool confirmation");
            out.line("Approved: " + plan.id + " v" + n + ", through Copilot's confirmation of this tool call "
                    + "(this Copilot client cannot show an approval form in the chat).");
            out.line("The approval covers exactly this version; any revision needs a new approval.");
        } catch (IOException e) {
            throw new ToolException("Could not read or save the plan: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while waiting for the developer's decision.");
        }
    }

    private void askInChat(PlanService service, Plan plan, PlanVersion v, ClientChannel client, Answer out)
            throws IOException, InterruptedException {
        Set<String> files = new LinkedHashSet<>();
        for (PlanChange c : v.changes) {
            files.add(c.path == null ? c.file : c.path.substring(c.path.lastIndexOf('/') + 1));
        }
        String message = "Approve plan " + plan.id + " v" + v.number + " \"" + plan.title + "\"? " + v.changes.size() + " change"
                + (v.changes.size() == 1 ? "" : "s") + " in " + String.join(", ", files) + "; checked against the code"
                + (v.warnings.isEmpty() ? "." : ", " + v.warnings.size() + " thing" + (v.warnings.size() == 1 ? "" : "s")
                + " to look at.") + " Nothing is written until the implementation is started.";
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode decision = props.putObject("decision");
        decision.put("type", "string").put("title", "Decision");
        decision.putArray("enum").add("approve").add("changes");
        decision.putArray("enumNames").add("Approve v" + v.number).add("Request changes");
        props.putObject("feedback").put("type", "string").put("title", "What should change? (for Request changes)");
        schema.putArray("required").add("decision");

        JsonNode answer = client.elicit(message, schema, FORM_TIMEOUT);
        String action = answer == null ? "none" : answer.path("action").asText("none");
        String choice = answer == null ? "" : answer.path("content").path("decision").asText("");
        if (action.equals("accept") && choice.equals("approve")) {
            service.approve(plan, v, "chat form");
            out.line("Approved by the developer in the chat: " + plan.id + " v" + v.number + ".");
            out.line("The approval covers exactly this version; any revision needs a new approval.");
        } else if (action.equals("accept") && choice.equals("changes")) {
            String feedback = answer.path("content").path("feedback").asText("");
            service.requestChanges(plan, feedback);
            out.line("The developer asked for changes to " + plan.id + " v" + v.number + ": "
                    + (feedback.isBlank() ? "(no details given; ask them what should change)" : feedback));
            out.line("Revise with plan_submit (plan " + plan.id + "), recording any rule they stated in decisions_add.");
        } else if (action.equals("decline")) {
            out.line("The developer declined to approve " + plan.id + " v" + v.number + ". Ask what should change.");
        } else {
            out.line("No decision came from the chat (the form was dismissed or not answered in "
                    + FORM_TIMEOUT.toMinutes() + " minutes). " + plan.id + " stays a draft; ask again when they are ready.");
        }
    }
}
