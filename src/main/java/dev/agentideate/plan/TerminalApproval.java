package dev.agentideate.plan;

import dev.agentideate.workspace.WorkspaceService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * {@code agentideate approve <plan> [version]}: the strictest approval. The developer reads the
 * plan in their own terminal and types {@code y}; no agent is involved at all.
 */
public final class TerminalApproval {

    private TerminalApproval() {
    }

    /** @return 0 approved, 1 not approved or not approvable */
    public static int run(WorkspaceService.Snapshot snapshot, String planId, Integer version, InputStream stdin,
                          PrintStream out, PrintStream err, long now) throws IOException {
        PlanService service = new PlanService(snapshot, now);
        Plan plan = service.store().get(planId);
        if (plan == null) {
            err.println("No plan '" + planId + "'. 'agentideate plans' lists them.");
            return 1;
        }
        PlanVersion v = version == null ? plan.latest() : plan.version(version);
        if (v == null) {
            err.println(plan.id + " has no v" + version + ".");
            return 1;
        }
        String blocker = service.approvalBlocker(plan, v);
        service.store().save(plan);
        PlanRenderer.render(plan, v, snapshot.workspace().multiRoot(), true, out::println);
        out.println();
        if (blocker != null) {
            err.println("Not approvable: " + blocker);
            return 1;
        }
        if (plan.approved() && plan.approvedVersion == v.number) {
            out.println(plan.id + " v" + v.number + " is already approved (" + plan.approvedVia + ").");
            return 0;
        }
        out.print("Approve " + plan.id + " v" + v.number + "? Nothing is written until implementation is started. [y/N] ");
        out.flush();
        String answer = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)).readLine();
        if (answer != null && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
            service.approve(plan, v, "terminal");
            out.println("Approved: " + plan.id + " v" + v.number + ".");
            return 0;
        }
        out.println("Not approved.");
        return 1;
    }
}
