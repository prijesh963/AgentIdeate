package dev.agentideate.implement;

import dev.agentideate.exec.CommandRunner;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanChange;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.tools.ImplementReport;
import dev.agentideate.workspace.WorkspaceService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** {@code agentideate implement <plan>} and {@code agentideate rollback <run>}: Workflow 3 from the terminal. */
public final class TerminalImplement {

    private TerminalImplement() {
    }

    public static int implement(WorkspaceService.Snapshot snapshot, CommandRunner runner, String planId, boolean compile,
                                InputStream stdin, PrintStream out, PrintStream err, long now) throws IOException {
        Plan plan = new PlanStore(snapshot.stateDir()).get(planId);
        if (plan == null) {
            err.println("No plan '" + planId + "'. 'agentideate plans' lists them.");
            return 1;
        }
        Implementer impl = new Implementer(snapshot, runner, now);
        String blocker = impl.blocker(plan);
        if (blocker != null) {
            err.println("Not implemented: " + blocker);
            return 1;
        }
        out.println("Plan " + plan.id + " v" + plan.latest().number + ": " + plan.title + " (approved, " + plan.approvedVia + ")");
        for (PlanChange c : plan.latest().changes) {
            out.println("  " + c.id + "  " + c.kind + "  " + c.file);
        }
        out.print("Write these changes now? Originals are backed up and the run can be undone. [y/N] ");
        out.flush();
        String answer = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)).readLine();
        if (answer == null || !(answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
            out.println("Not started; nothing was written.");
            return 1;
        }
        Implementer.Outcome o = impl.implement(plan, "terminal", compile, false, Implementer.DEFAULT_COMPILE_TIMEOUT);
        ImplementReport.lines(plan, o).forEach(out::println);
        return o.applied() ? 0 : 1;
    }

    public static int rollback(WorkspaceService.Snapshot snapshot, CommandRunner runner, String id, PrintStream out,
                               PrintStream err, long now) throws IOException {
        if (id.startsWith("p")) {
            Plan plan = new PlanStore(snapshot.stateDir()).get(id);
            if (plan == null || plan.implementedRun == null) {
                err.println(id + " has no implementation run to undo.");
                return 1;
            }
            id = plan.implementedRun;
        }
        Implementer impl = new Implementer(snapshot, runner, now);
        RunRecord run = impl.load(id);
        if (run == null) {
            err.println("No run '" + id + "'.");
            return 1;
        }
        if ("rolled_back".equals(run.status)) {
            out.println("Run " + run.id + " was already rolled back.");
            return 0;
        }
        List<String> skipped = impl.undo(run);
        out.println("Undid run " + run.id + " (" + run.plan + " v" + run.version + ")" + (skipped.isEmpty() ? "." : ", except:"));
        skipped.forEach(s -> out.println("  left alone: " + s));
        return skipped.isEmpty() ? 0 : 1;
    }
}
