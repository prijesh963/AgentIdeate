package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.graph.ReverseIndex;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.review.ChangedCode;
import dev.agentideate.review.Reviewer;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.util.List;

/**
 * Workflow 4. Without findings: the computed conformance of the run to its approved plan, then a
 * review pack (each change's code as it is now, the methods it falls in, their callers and tests)
 * for the agent to read. With findings: each one is checked against a real file and line, and the
 * review is recorded.
 */
final class PlanReviewTool implements Tool {

    static final int MAX_REGION_LINES = 15;
    static final int MAX_CALLERS = 5;

    private final ToolRegistry registry;

    PlanReviewTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "plan_review";
    }

    @Override
    public String description() {
        return "Check an implemented plan: only the approved changes, intact, nothing else changed. "
                + "Returns a review pack; call again with findings to record them.";
    }

    @Override
    public boolean threaded() {
        return false;
    }

    @Override
    public int budgetChars() {
        return 12_000;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("plan").put("type", "string");
        ObjectNode findings = p.putObject("findings");
        findings.put("type", "array").put("description", "{file, line, text, severity: blocker|major|minor|nit}");
        findings.putObject("items").put("type", "object");
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
            Reviewer reviewer = new Reviewer(snapshot, registry.runner(), registry.now());
            Reviewer.Report r = reviewer.review(plan);
            conformance(r, out);
            JsonNode findings = args.node() == null ? null : args.node().get("findings");
            if (findings != null && findings.isArray() && !findings.isEmpty()) {
                Reviewer.ReviewRecord rec = reviewer.record(r, findings);
                out.line("Recorded " + rec.findings.size() + " finding" + (rec.findings.size() == 1 ? "" : "s") + ":");
                rec.findings.forEach(f -> out.line("  " + f.severity + "  " + f.file + ":" + f.line + "  " + f.text));
                if (!rec.rejected.isEmpty()) {
                    out.line("Rejected (not recorded; check the file and line, then send them again):");
                    rec.rejected.forEach(x -> out.line("  " + x));
                }
                return;
            }
            reviewer.record(r, null);
            pack(r, snapshot, graph, out);
        } catch (Reviewer.ReviewException e) {
            throw new ToolException(e.getMessage());
        } catch (IOException e) {
            throw new ToolException("Review stopped: " + e.getMessage());
        }
    }

    static void conformance(Reviewer.Report r, Answer out) {
        out.line("Review of " + r.plan().id + " v" + r.version().number + " \"" + r.plan().title + "\" (run " + r.run().id
                + ", started via " + r.run().via + "; compile " + (r.run().compileStatus == null ? "not run" : r.run().compileStatus) + ")");
        out.line("Verdict: " + r.verdict() + switch (r.verdict()) {
            case "CONFORMS" -> ": every approved change is in place, unchanged since the run"
                    + (r.notChecked().isEmpty() ? ", and git shows nothing else changed." : "; git was not checked (below).");
            case "DEVIATES" -> ": the project differs from what was approved and written (below).";
            default -> ": not everything approved is in the project (below).";
        });
        if (!"applied".equals(r.run().status)) {
            out.line("  the run is " + r.run().status + (r.run().note == null ? "" : ": " + r.run().note));
        }
        for (Reviewer.FileCheck f : r.files()) {
            out.line(String.format("  %-7s %s %s %s", f.status(), f.change(), f.kind(), f.display())
                    + (f.details().isEmpty() ? "" : ": " + String.join("; ", f.details())));
        }
        r.notInRun().forEach(c -> out.line("  MISSING " + c + ": approved but not in the run"));
        if (!r.unplanned().isEmpty()) {
            out.line("Not in the plan: changed since the run started (yours, or the agent's outside the plan): "
                    + String.join(", ", r.unplanned()));
        } else if (r.notChecked().isEmpty()) {
            out.line("Not in the plan: nothing (git).");
        }
        r.notChecked().forEach(n -> out.line("Not in the plan: not checked (" + n + ")"));
        r.otherServices().forEach(o -> out.line("Other services: " + o));
    }

    private static void pack(Reviewer.Report r, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) {
        List<ChangedCode.Region> regions = ChangedCode.regions(snapshot.workspace(), graph, r.version());
        out.line("Review pack (the code as it is now; plan_show " + r.plan().id + " has the approved diff):");
        for (ChangedCode.Region g : regions) {
            if (!g.located()) {
                out.line("  " + g.change().id + " " + g.display() + ": "
                        + (g.change().after == null || g.change().after.isEmpty() ? "removes lines only" : "new text not found"));
                continue;
            }
            out.line("  " + g.change().id + " " + (g.file() != null ? out.ref(g.file(), g.start()) : g.display() + ":" + g.start())
                    + (g.end() > g.start() ? "-" + g.end() : ""));
            int shown = Math.min(g.lines().size(), MAX_REGION_LINES);
            for (int i = 0; i < shown; i++) {
                out.line(String.format("    %4d | %s", g.start() + i, g.lines().get(i)));
            }
            if (g.lines().size() > shown) {
                out.line("         ... " + (g.lines().size() - shown) + " more lines");
            }
        }
        List<ChangedCode.Target> targets = ChangedCode.methods(graph, regions);
        if (!targets.isEmpty()) {
            out.line("Changed methods, who calls them, what tests them:");
            ReverseIndex rev = graph.reverse();
            for (ChangedCode.Target t : targets) {
                out.line("  " + t.owner().name() + "." + t.method().name() + "  " + out.ref(t.owner().file(), t.method().line())
                        + " (" + t.change() + ")");
                List<ReverseIndex.Ref> callers = rev.callersOf(t.owner(), t.method().name()).stream()
                        .filter(c -> c.owner() != t.owner() || c.method() != t.method()).toList();
                callers.stream().limit(MAX_CALLERS).forEach(c ->
                        out.line("    called by " + c.owner().name() + "." + c.method().name() + "  " + out.ref(c.owner().file(), c.line())));
                if (callers.size() > MAX_CALLERS) {
                    out.line("    +" + (callers.size() - MAX_CALLERS) + " more callers (impact_of " + t.owner().name() + "#" + t.method().name() + ")");
                }
                if (callers.isEmpty()) {
                    out.line("    called by: none found in the index (an entry point, or called reflectively)");
                }
                List<TypeNode> tests = rev.testsOf(t.owner());
                out.line("    tests: " + (tests.isEmpty() ? "none found (test_pack proposes one)"
                        : String.join(", ", tests.stream().map(x -> x.name() + " " + out.ref(x.file())).toList())));
            }
        }
        out.line("Check: inputs at each changed method (null, empty, bounds); each caller still gets what it expects; "
                + "transactions and exceptions; messages, tables and APIs other services rely on; tests for the new behaviour.");
        out.line("Record what you find: plan_review plan=" + r.plan().id + " findings=[{file, line, text, severity}]. "
                + "Each is checked against the file; give real lines.");
    }
}
