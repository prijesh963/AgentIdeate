package dev.agentideate.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders a plan version for review: what will change, where, from what to what, why, and what
 * validation found. Built to be read by the developer, not only the agent: each edit shows the
 * current lines and their replacement with line numbers, so "state A to state B" is literal.
 */
public final class PlanRenderer {

    static final int MAX_LINES_PER_SIDE = 40;

    private PlanRenderer() {
    }

    /** @param full show every change's lines, not only those new or changed in this version */
    public static void render(Plan plan, PlanVersion v, boolean multiRoot, boolean full, Consumer<String> out) {
        String state = plan.implemented() && plan.approvedVersion == v.number ? "IMPLEMENTED (run " + plan.implementedRun + ")"
                : plan.approved() && plan.approvedVersion == v.number ? "APPROVED (" + plan.approvedVia + ")"
                : "abandoned".equals(plan.status) ? "abandoned" : "draft";
        out.accept("Plan " + plan.id + " v" + v.number + ": " + plan.title + "   [" + state + " · " + v.changes.size()
                + " change" + (v.changes.size() == 1 ? "" : "s") + " · " + (v.errors.isEmpty() ? "valid" : v.errors.size() + " problems") + "]");
        if (plan.story != null) {
            out.accept("Story: " + plan.story);
        }
        if (v.feedback != null) {
            out.accept("Feedback this version answers: " + v.feedback);
        }
        out.accept("Understanding: " + (v.understanding == null ? "(missing)" : v.understanding));
        if (!plan.decisions.isEmpty()) {
            out.accept("Decisions (every version keeps to these):");
            for (Decision d : plan.decisions) {
                out.accept("  " + d.id + "  " + d.text + (d.forbid.isEmpty() ? "" : "  [rules out: " + String.join(", ", d.forbid) + "]")
                        + "  (from v" + d.fromVersion + ")");
            }
        }
        out.accept("Changes:");
        for (PlanChange c : v.changes) {
            String status = v.changeStatus.getOrDefault(c.id, "new");
            String tag = v.number == 1 ? "" : status.equals("same") ? "  [unchanged]" : status.equals("new") ? "  [new in v" + v.number + "]"
                    : "  [changed in v" + v.number + "]";
            String where = c.path == null ? c.file : (multiRoot ? c.root + "/" + c.path : c.path);
            String lines = switch (c.kind == null ? "" : c.kind) {
                case "edit" -> c.startLine > 0 ? " lines " + c.startLine + "-" + c.endLine : " (not located)";
                case "add" -> " (new file, " + c.endLine + " lines)";
                case "delete" -> " (whole file, " + c.endLine + " lines)";
                default -> "";
            };
            out.accept("  " + c.id + "  " + c.kind + "  " + where + lines + tag);
            if (c.reason != null) {
                out.accept("      why: " + c.reason);
            }
            if (!c.evidence.isEmpty()) {
                out.accept("      based on: " + String.join(", ", c.evidence));
            }
            if (!full && status.equals("same") && v.number > 1) {
                continue; // shown in the version that introduced it
            }
            if ("edit".equals(c.kind) && c.before != null) {
                side(out, "-", c.before, Math.max(1, c.startLine));
                side(out, "+", c.after == null ? "" : c.after, Math.max(1, c.startLine));
            } else if ("add".equals(c.kind) && c.after != null) {
                side(out, "+", c.after, 1);
            }
        }
        if (!v.removed.isEmpty()) {
            out.accept("Dropped since v" + (v.number - 1) + ": " + String.join("; ", v.removed));
        }
        if (v.errors.isEmpty()) {
            out.accept("Checks: valid against the current code" + (v.warnings.isEmpty() ? "." : "; " + v.warnings.size()
                    + " thing" + (v.warnings.size() == 1 ? "" : "s") + " to look at:"));
        } else {
            out.accept("Checks: " + v.errors.size() + " problem" + (v.errors.size() == 1 ? "" : "s") + ", not approvable until fixed:");
            v.errors.forEach(e -> out.accept("  x " + e));
        }
        v.warnings.forEach(w -> out.accept("  ! " + w));
    }

    private static void side(Consumer<String> out, String sign, String text, int firstLine) {
        if (text.isEmpty()) {
            out.accept("      " + sign + "        (nothing: the lines above are removed)");
            return;
        }
        List<String> lines = new ArrayList<>(text.replace("\r\n", "\n").lines().toList());
        int shown = Math.min(lines.size(), MAX_LINES_PER_SIDE);
        for (int i = 0; i < shown; i++) {
            out.accept(String.format("      %s %4d | %s", sign, firstLine + i, lines.get(i)));
        }
        if (lines.size() > shown) {
            out.accept("      " + sign + "      ... " + (lines.size() - shown) + " more lines");
        }
    }

    /** One line per plan, for listings. */
    public static String summary(Plan plan) {
        PlanVersion v = plan.latest();
        String state = plan.implemented() ? "implemented v" + plan.approvedVersion + " (run " + plan.implementedRun + ")"
                : plan.approved() ? "approved v" + plan.approvedVersion + " (" + plan.approvedVia + ")" : plan.status;
        return plan.id + "  v" + (v == null ? 0 : v.number) + "  " + state + "  " + (v == null ? 0 : v.changes.size()) + " changes"
                + (v != null && !v.errors.isEmpty() ? ", " + v.errors.size() + " problems" : "")
                + (plan.pendingFeedback != null ? ", changes requested" : "") + "  " + plan.title;
    }
}
