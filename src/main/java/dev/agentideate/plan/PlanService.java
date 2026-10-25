package dev.agentideate.plan;

import com.fasterxml.jackson.databind.JsonNode;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow 2: drafting a plan, revising it on feedback, approving it. Creates plans and versions,
 * applies revisions as patches (changes not mentioned carry over untouched, so they cannot drift),
 * keeps the developer's decisions, validates every version against the code, and decides whether an
 * approval may be recorded.
 */
public final class PlanService {

    /** Raised for a request that cannot be carried out; the message says why and what to do. */
    public static final class PlanException extends Exception {
        public PlanException(String message) {
            super(message);
        }
    }

    private final WorkspaceService.Snapshot snapshot;
    private final PlanStore store;
    private final long now;

    public PlanService(WorkspaceService.Snapshot snapshot, long now) {
        this.snapshot = snapshot;
        this.store = new PlanStore(snapshot.stateDir());
        this.now = now;
    }

    public PlanStore store() {
        return store;
    }

    /**
     * Creates a plan (no {@code plan} argument) or a new version of one. A revision is a patch:
     * {@code changes} with an existing id replace that change, without an id they are added;
     * {@code remove} drops changes by id; every other change carries over as it was.
     */
    public Plan submit(JsonNode args) throws IOException, PlanException {
        String planId = text(args, "plan");
        Plan plan;
        List<PlanChange> changes;
        String feedback = text(args, "feedback");
        if (planId == null) {
            String title = text(args, "title");
            if (title == null) {
                throw new PlanException("A new plan needs a title (and ideally the story it implements).");
            }
            plan = new Plan();
            plan.id = store.nextId();
            plan.title = title;
            plan.story = text(args, "story");
            plan.created = now;
            changes = new ArrayList<>();
        } else {
            plan = store.get(planId);
            if (plan == null) {
                throw new PlanException("No plan '" + planId + "'. plan_show lists the plans.");
            }
            if ("abandoned".equals(plan.status)) {
                throw new PlanException(plan.id + " was abandoned; start a new plan.");
            }
            changes = new ArrayList<>();
            plan.latest().changes.forEach(c -> changes.add(c.copy()));
            if (feedback == null) {
                feedback = plan.pendingFeedback;
            }
        }

        for (String id : list(args, "remove")) {
            if (!changes.removeIf(c -> c.id.equals(id))) {
                throw new PlanException("remove: there is no change '" + id + "' in the latest version.");
            }
        }
        JsonNode given = args == null ? null : args.get("changes");
        if (given != null && given.isArray()) {
            for (JsonNode g : given) {
                PlanChange c = change(g);
                if (c.id != null) {
                    int idx = indexOf(changes, c.id);
                    if (idx < 0) {
                        throw new PlanException("There is no change '" + c.id + "' to replace; omit the id to add a new change.");
                    }
                    changes.set(idx, c);
                } else {
                    c.id = "c" + plan.nextChange++;
                    changes.add(c);
                }
            }
        }
        for (String id : list(args, "decisions_drop")) {
            if (!plan.decisions.removeIf(d -> d.id.equals(id))) {
                throw new PlanException("decisions_drop: there is no decision '" + id + "'.");
            }
        }

        PlanVersion previous = plan.latest();
        PlanVersion v = new PlanVersion();
        v.number = previous == null ? 1 : previous.number + 1;
        v.created = now;
        String understanding = text(args, "understanding");
        v.understanding = understanding != null ? understanding : previous == null ? null : previous.understanding;
        v.feedback = feedback;
        v.changes = changes;

        JsonNode adds = args == null ? null : args.get("decisions_add");
        if (adds != null && adds.isArray()) {
            for (JsonNode a : adds) {
                Decision d = new Decision();
                d.id = "d" + plan.nextDecision++;
                d.text = a.isTextual() ? a.asText() : a.path("text").asText("");
                if (a.has("forbid") && a.get("forbid").isArray()) {
                    a.get("forbid").forEach(f -> d.forbid.add(f.asText()));
                }
                d.fromVersion = v.number;
                d.created = now;
                if (!d.text.isBlank()) {
                    plan.decisions.add(d);
                }
            }
        }

        compare(previous, v);
        validate(plan, v);
        plan.versions.add(v);
        plan.pendingFeedback = null;
        if (plan.approved()) {
            plan.status = "draft"; // a new version replaces what was approved; it needs its own approval
            plan.approvedVersion = null;
            plan.approvedHash = null;
            plan.approvedVia = null;
            plan.approvedFileHashes = new LinkedHashMap<>();
            plan.implementedRun = null;
        }
        plan.updated = now;
        store.save(plan);
        return plan;
    }

    /** Re-checks a version against the files as they are now (they may have changed since). */
    public void validate(Plan plan, PlanVersion v) {
        PlanValidator.Result r = new PlanValidator(snapshot.workspace(), snapshot.graph()).validate(plan, v);
        v.errors = new ArrayList<>(r.errors());
        v.warnings = new ArrayList<>(r.warnings());
        v.fileHashes = new LinkedHashMap<>(r.fileHashes());
        v.contentHash = contentHash(v);
        v.validated = now;
    }

    /** Why an approval of this version cannot be recorded now, or {@code null} when it can. */
    public String approvalBlocker(Plan plan, PlanVersion v) {
        if ("abandoned".equals(plan.status)) {
            return plan.id + " was abandoned.";
        }
        if (v != plan.latest()) {
            return "v" + v.number + " is not the latest version (v" + plan.latest().number + "); only the latest can be approved.";
        }
        validate(plan, v); // the files may have changed since it was last checked
        if (!v.errors.isEmpty()) {
            return "v" + v.number + " has " + v.errors.size() + " problem" + (v.errors.size() == 1 ? "" : "s")
                    + " against the current code; fix them in a new version first:\n  " + String.join("\n  ", v.errors);
        }
        return null;
    }

    public void approve(Plan plan, PlanVersion v, String via) throws IOException {
        plan.status = "approved";
        plan.approvedVersion = v.number;
        plan.approvedAt = now;
        plan.approvedVia = via;
        plan.approvedHash = v.contentHash;
        plan.approvedFileHashes = new LinkedHashMap<>(v.fileHashes);
        plan.pendingFeedback = null;
        plan.updated = now;
        store.save(plan);
    }

    public void requestChanges(Plan plan, String feedback) throws IOException {
        plan.pendingFeedback = feedback == null || feedback.isBlank() ? "(the developer gave no details)" : feedback;
        plan.updated = now;
        store.save(plan);
    }

    /** Marks each change new/changed/same against the previous version, and lists what was dropped. */
    static void compare(PlanVersion previous, PlanVersion v) {
        Map<String, PlanChange> old = new LinkedHashMap<>();
        if (previous != null) {
            previous.changes.forEach(c -> old.put(c.id, c));
        }
        for (PlanChange c : v.changes) {
            PlanChange o = old.remove(c.id);
            v.changeStatus.put(c.id, o == null ? "new" : o.content().equals(c.content()) ? "same" : "changed");
        }
        old.values().forEach(c -> v.removed.add(c.id + " " + c.kind + " " + c.file));
    }

    static String contentHash(PlanVersion v) {
        StringBuilder sb = new StringBuilder(v.understanding == null ? "" : v.understanding);
        for (PlanChange c : v.changes) {
            sb.append('\u0001').append(c.id).append('\u0000').append(c.content());
        }
        return PlanValidator.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static PlanChange change(JsonNode g) throws PlanException {
        if (!g.isObject()) {
            throw new PlanException("Each change must be an object with kind, file, and before/after.");
        }
        PlanChange c = new PlanChange();
        c.id = text(g, "id");
        c.kind = text(g, "kind");
        c.file = text(g, "file");
        c.reason = text(g, "reason");
        c.before = g.has("before") && !g.get("before").isNull() ? g.get("before").asText() : null;
        c.after = g.has("after") && !g.get("after").isNull() ? g.get("after").asText() : null;
        if (g.has("evidence") && g.get("evidence").isArray()) {
            g.get("evidence").forEach(e -> c.evidence.add(e.asText()));
        }
        return c;
    }

    private static int indexOf(List<PlanChange> changes, String id) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        String s = n.get(field).isTextual() ? n.get(field).asText() : n.get(field).toString();
        return s.isBlank() ? null : s;
    }

    private static List<String> list(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        if (n != null && n.has(field) && n.get(field).isArray()) {
            n.get(field).forEach(e -> out.add(e.asText()));
        }
        return out;
    }
}
