package dev.agentideate.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanRenderer;
import dev.agentideate.plan.PlanService;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;

/**
 * Workflow 2, drafting: submit a plan or a revision. Nothing in the project changes; the plan is
 * validated against the code and shown for review.
 */
final class PlanSubmitTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolRegistry registry;

    PlanSubmitTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "plan_submit";
    }

    @Override
    public String description() {
        return "Propose a code change as a plan; nothing is written. Edits quote exact current text. "
                + "Revise: pass plan; changes with an id replace it, without are added.";
    }

    @Override
    public boolean threaded() {
        return false;
    }

    @Override
    public int budgetChars() {
        return 20_000;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("plan").put("type", "string").put("description", "Omit for a new plan");
        p.putObject("title").put("type", "string");
        p.putObject("story").put("type", "string");
        p.putObject("understanding").put("type", "string").put("description", "Story in your words, and approach");
        ObjectNode change = p.putObject("changes").put("type", "array").putObject("items");
        change.put("type", "object");
        ObjectNode cp = change.putObject("properties");
        cp.putObject("id").put("type", "string");
        cp.putObject("kind").put("type", "string").putArray("enum").add("edit").add("add").add("delete");
        cp.putObject("file").put("type", "string");
        cp.putObject("before").put("type", "string").put("description", "edit: exact text, once");
        cp.putObject("after").put("type", "string").put("description", "edit: new text; add: content");
        cp.putObject("reason").put("type", "string");
        cp.putObject("evidence").put("type", "array").put("description", "Class or Class#method")
                .putObject("items").put("type", "string");
        change.putArray("required").add("kind").add("file");
        p.putObject("remove").put("type", "array").putObject("items").put("type", "string");
        p.putObject("feedback").put("type", "string");
        ObjectNode decision = p.putObject("decisions_add").put("type", "array")
                .put("description", "Developer's rules; forbid: path globs").putObject("items");
        decision.put("type", "object");
        decision.putObject("properties").putObject("text").put("type", "string");
        ((ObjectNode) decision.get("properties")).putObject("forbid").put("type", "array").putObject("items").put("type", "string");
        p.putObject("decisions_drop").put("type", "array").putObject("items").put("type", "string");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        PlanService service = new PlanService(snapshot, registry.now());
        Plan plan;
        try {
            plan = service.submit(args.node() == null ? JSON.createObjectNode() : args.node());
        } catch (PlanService.PlanException e) {
            throw new ToolException(e.getMessage());
        } catch (IOException e) {
            throw new ToolException("Could not save the plan: " + e.getMessage());
        }
        var v = plan.latest();
        PlanRenderer.render(plan, v, snapshot.workspace().multiRoot(), false, out::line);
        out.blank();
        if (v.errors.isEmpty()) {
            out.line("Next: present this plan to the developer (what changes, where, and why). They approve with "
                    + "plan_approve (plan " + plan.id + ") or ask for changes; for changes, record any rule they state in "
                    + "decisions_add and resubmit with plan " + plan.id + ".");
        } else {
            out.line("Next: fix the problems above and resubmit with plan " + plan.id
                    + " (send only the changes that change, with their ids). Do not show an invalid plan as ready.");
        }
    }
}
