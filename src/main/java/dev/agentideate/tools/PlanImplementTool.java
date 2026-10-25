package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.implement.Implementer;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanChange;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Workflow 3: the second human gate, then the write. Approval said "this plan is right"; starting
 * says "write it now". Both are the developer's: a Start form in the chat (or Copilot's
 * confirmation of this call when the client has no forms), or the terminal in terminal mode.
 */
final class PlanImplementTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final Duration FORM_TIMEOUT = Duration.ofMinutes(15);

    private final ToolRegistry registry;

    PlanImplementTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "plan_implement";
    }

    @Override
    public String description() {
        return "Write an approved plan into the project, after the developer starts it in the chat. All or nothing; "
                + "verified, compiled, undoable.";
    }

    @Override
    public boolean threaded() {
        return false;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("plan").put("type", "string");
        p.putObject("compile").put("type", "boolean").put("description", "Default true");
        s.putArray("required").add("plan");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        try {
            Plan plan = new PlanStore(snapshot.stateDir()).get(args.required("plan"));
            if (plan == null) {
                throw new ToolException("No plan '" + args.string("plan") + "'. plan_show lists the plans.");
            }
            Implementer impl = new Implementer(snapshot, registry.runner(), registry.now());
            String blocker = impl.blocker(plan);
            if (blocker != null) {
                throw new ToolException("Not implemented: " + blocker);
            }
            if ("terminal".equals(registry.approvalMode())) {
                out.line("This team starts implementations in the terminal. Ask the developer to run:");
                out.line("  java -jar agentideate.jar implement " + plan.id);
                return;
            }
            String via;
            ClientChannel client = registry.client();
            if (client != null && client.supportsElicitation()) {
                if (!startedInChat(plan, client, out)) {
                    return;
                }
                via = "chat form";
            } else {
                via = "Copilot tool confirmation";
            }
            boolean compile = registry.compileEnabled() && !"false".equals(args.string("compile"));
            Implementer.Outcome o = impl.implement(plan, via, compile, false, Implementer.DEFAULT_COMPILE_TIMEOUT);
            ImplementReport.lines(plan, o).forEach(out::line);
        } catch (IOException e) {
            throw new ToolException("Implementation stopped: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while waiting for the developer to start.");
        }
    }

    private boolean startedInChat(Plan plan, ClientChannel client, Answer out) throws InterruptedException {
        var v = plan.latest();
        Set<String> files = new LinkedHashSet<>();
        for (PlanChange c : v.changes) {
            files.add(c.kind + " " + (c.path == null ? c.file : c.path.substring(c.path.lastIndexOf('/') + 1)));
        }
        String message = "Start implementing " + plan.id + " v" + v.number + " \"" + plan.title + "\"? This writes: "
                + String.join(", ", files) + ". Originals are backed up and the run can be undone.";
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode decision = schema.putObject("properties").putObject("decision");
        decision.put("type", "string").put("title", "Decision");
        decision.putArray("enum").add("start").add("wait");
        decision.putArray("enumNames").add("Start: write " + files.size() + " file" + (files.size() == 1 ? "" : "s")).add("Not now");
        schema.putArray("required").add("decision");
        JsonNode answer = client.elicit(message, schema, FORM_TIMEOUT);
        boolean start = answer != null && answer.path("action").asText().equals("accept")
                && answer.path("content").path("decision").asText().equals("start");
        if (!start) {
            out.line("Not started: the developer chose not to start " + plan.id + " now (or the form was not answered). Nothing was written.");
        }
        return start;
    }
}
