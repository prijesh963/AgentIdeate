package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.implement.Implementer;
import dev.agentideate.implement.RunRecord;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.util.List;

/** Undoes an implementation run from its backups; a file edited since the run is left alone. */
final class PlanRollbackTool implements Tool {

    private final ToolRegistry registry;

    PlanRollbackTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "plan_rollback";
    }

    @Override
    public String description() {
        return "Undo an implementation run (run id, or plan id for its run). Files edited since are left alone.";
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
        ((ObjectNode) s.get("properties")).putObject("run").put("type", "string").put("description", "r1, or a plan id");
        s.putArray("required").add("run");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        try {
            String id = args.required("run");
            if (id.startsWith("p")) {
                Plan plan = new PlanStore(snapshot.stateDir()).get(id);
                if (plan == null || plan.implementedRun == null) {
                    throw new ToolException(id + " has no implementation run to undo.");
                }
                id = plan.implementedRun;
            }
            Implementer impl = new Implementer(snapshot, registry.runner(), registry.now());
            RunRecord run = impl.load(id);
            if (run == null) {
                throw new ToolException("No run '" + id + "'.");
            }
            if ("rolled_back".equals(run.status)) {
                out.line("Run " + run.id + " was already rolled back.");
                return;
            }
            List<String> skipped = impl.undo(run);
            out.line("Undid run " + run.id + " (" + run.plan + " v" + run.version + "): " + run.files.size() + " file"
                    + (run.files.size() == 1 ? "" : "s") + " restored" + (skipped.isEmpty() ? "." : ", except:"));
            skipped.forEach(s -> out.line("  left alone: " + s));
            out.line("The plan is approved again, not implemented; plan_implement can apply it once more.");
        } catch (IOException e) {
            throw new ToolException("Undo stopped: " + e.getMessage());
        }
    }
}
