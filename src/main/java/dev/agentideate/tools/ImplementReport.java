package dev.agentideate.tools;

import dev.agentideate.implement.Implementer;
import dev.agentideate.implement.RunRecord;
import dev.agentideate.plan.Plan;

import java.util.ArrayList;
import java.util.List;

/** The account of an implementation run, the same in the chat and in the terminal. */
public final class ImplementReport {

    private ImplementReport() {
    }

    public static List<String> lines(Plan plan, Implementer.Outcome o) {
        List<String> out = new ArrayList<>();
        if (o.run() == null) {
            out.add("Not implemented: " + String.join(" ", o.problems()));
            return out;
        }
        RunRecord run = o.run();
        if (o.applied()) {
            out.add("Implemented " + plan.id + " v" + run.version + " (run " + run.id + "), started via " + run.via + ".");
        } else {
            out.add("Rolled back " + plan.id + " v" + run.version + " (run " + run.id + "): " + String.join(" ", o.problems()));
        }
        out.add("Files:");
        if (run.verify.isEmpty()) {
            run.files.forEach(f -> out.add("  " + f.kind + " " + f.path));
        } else {
            run.verify.forEach(v -> out.add("  " + v));
        }
        List<String> notChecked = run.outsidePlan.stream().filter(p -> p.startsWith("(")).toList();
        List<String> outside = run.outsidePlan.stream().filter(p -> !p.startsWith("(")).toList();
        notChecked.forEach(p -> out.add("Outside the plan: " + p));
        if (!outside.isEmpty()) {
            out.add("Outside the plan: these files changed during the run and are not part of it: " + String.join(", ", outside));
        } else if (notChecked.isEmpty()) {
            out.add("Outside the plan: nothing else changed (git).");
        }
        out.add("Compile: " + compileLine(run));
        o.compileDigest().stream().limit(25).forEach(l -> out.add("  " + l));
        if (o.applied()) {
            out.add("Undo with plan_rollback (run " + run.id + ") or `java -jar agentideate.jar rollback " + run.id + "`.");
        }
        return out;
    }

    static String compileLine(RunRecord run) {
        return switch (run.compileStatus == null ? "" : run.compileStatus) {
            case "passed" -> "passed (" + run.compileCommand + ")";
            case "failed" -> "FAILED with compile errors (" + run.compileCommand + ")";
            case "skipped" -> "skipped (turned off)";
            case "timeout" -> "stopped at the time limit; the changes are kept, compile them yourself";
            case "not-run" -> "could not be completed, for a reason other than the plan's code (below); the changes are kept, "
                    + "compile them yourself";
            default -> "not run";
        };
    }
}
