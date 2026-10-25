package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanRenderer;
import dev.agentideate.plan.PlanService;
import dev.agentideate.plan.PlanVersion;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.util.List;

/** Workflow 2, reviewing: a plan in full, re-checked against the current code; or the list of plans. */
final class PlanShowTool implements Tool {

    private final ToolRegistry registry;

    PlanShowTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "plan_show";
    }

    @Override
    public String description() {
        return "Show a plan for review, re-checked against the current code (default: latest version). No plan id lists plans.";
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
        p.putObject("plan").put("type", "string");
        p.putObject("version").put("type", "integer");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        PlanService service = new PlanService(snapshot, registry.now());
        try {
            String id = args.string("plan");
            if (id == null) {
                List<Plan> plans = service.store().all();
                if (plans.isEmpty()) {
                    out.line("No plans yet. plan_submit drafts one.");
                    return;
                }
                out.line("Plans (most recent first):");
                plans.forEach(p -> out.line("  " + PlanRenderer.summary(p)));
                return;
            }
            Plan plan = service.store().get(id);
            if (plan == null) {
                throw new ToolException("No plan '" + id + "'. plan_show without a plan id lists them.");
            }
            int n = args.integer("version", plan.latest().number, 1, Integer.MAX_VALUE);
            PlanVersion v = plan.version(n);
            if (v == null) {
                throw new ToolException(plan.id + " has no v" + n + "; it has v1 to v" + plan.latest().number + ".");
            }
            if (v == plan.latest()) {
                service.validate(plan, v); // show the plan as it stands against today's code
                service.store().save(plan);
            }
            PlanRenderer.render(plan, v, snapshot.workspace().multiRoot(), true, out::line);
            if (plan.pendingFeedback != null) {
                out.line("Changes requested by the developer, not yet answered: " + plan.pendingFeedback);
            }
            if (v != plan.latest()) {
                out.line("(an older version; the latest is v" + plan.latest().number + ")");
            }
        } catch (IOException e) {
            throw new ToolException("Could not read plans: " + e.getMessage());
        }
    }
}
