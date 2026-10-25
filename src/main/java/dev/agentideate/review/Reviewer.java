package dev.agentideate.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentideate.exec.CommandRunner;
import dev.agentideate.implement.AuditLog;
import dev.agentideate.implement.GitStatus;
import dev.agentideate.implement.Implementer;
import dev.agentideate.implement.RunRecord;
import dev.agentideate.index.SourceFile;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanChange;
import dev.agentideate.plan.PlanValidator;
import dev.agentideate.plan.PlanVersion;
import dev.agentideate.workspace.Workspace;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflow 4: checks that what was written is what was approved, and nothing else.
 *
 * <ul>
 *   <li>Each file of the run is still exactly what the run wrote (same hash); if not, each change's
 *       new text is looked for, and an edit's old text too, to say what drifted.</li>
 *   <li>Every change of the approved version is in the run.</li>
 *   <li>Git shows nothing changed since the run started that is neither the plan's nor was
 *       already changed before it.</li>
 * </ul>
 *
 * The verdict is computed, not judged: {@code CONFORMS}, {@code DEVIATES} or {@code INCOMPLETE}.
 * Findings from the agent's own reading are checked against real files and lines before they are
 * recorded, so a review cannot cite code that does not exist.
 */
public final class Reviewer {

    public static final class ReviewException extends Exception {
        public ReviewException(String message) {
            super(message);
        }
    }

    /** @param status {@code ok}, {@code CHANGED} (edited since the run) or {@code MISSING} */
    public record FileCheck(String change, String kind, String display, String status, List<String> details) {
    }

    public record Report(Plan plan, PlanVersion version, RunRecord run, String verdict, List<FileCheck> files,
                         List<String> notInRun, List<String> unplanned, List<String> notChecked, List<String> otherServices) {
    }

    public static final class Finding {
        public String file;
        public int line;
        public String severity;
        public String text;
    }

    public static final class ReviewRecord {
        public String plan;
        public int version;
        public String run;
        public long created;
        public String verdict;
        public List<Finding> findings = new ArrayList<>();
        public List<String> rejected = new ArrayList<>();
    }

    static final Set<String> SEVERITIES = Set.of("blocker", "major", "minor", "nit");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final WorkspaceService.Snapshot snapshot;
    private final CommandRunner runner;
    private final long now;

    public Reviewer(WorkspaceService.Snapshot snapshot, CommandRunner runner, long now) {
        this.snapshot = snapshot;
        this.runner = runner;
        this.now = now;
    }

    public Report review(Plan plan) throws IOException, ReviewException {
        if (plan.implementedRun == null) {
            throw new ReviewException(plan.id + " has no applied implementation to review (it is " + plan.status
                    + "). Review checks a run of plan_implement against its approved plan.");
        }
        RunRecord run = new Implementer(snapshot, runner, now).load(plan.implementedRun);
        PlanVersion v = run == null ? null : plan.version(run.version);
        if (v == null) {
            throw new ReviewException("The record of run " + plan.implementedRun + " (or the version it applied) is missing.");
        }
        Workspace ws = snapshot.workspace();
        List<FileCheck> files = new ArrayList<>();
        Set<String> inRun = new LinkedHashSet<>();
        for (RunRecord.FileRecord f : run.files) {
            inRun.addAll(List.of(f.change.split("\\+")));
            files.add(check(ws, v, f));
        }
        List<String> notInRun = new ArrayList<>();
        for (PlanChange c : v.changes) {
            if (!inRun.contains(c.id)) {
                notInRun.add(c.id + " " + c.kind + " " + c.file);
            }
        }
        List<String> unplanned = new ArrayList<>();
        List<String> notChecked = new ArrayList<>();
        unplanned(run, unplanned, notChecked);
        List<String> other = new PlanValidator(ws, snapshot.graph()).otherServiceEffects(v);

        String verdict;
        if (!"applied".equals(run.status) || !notInRun.isEmpty() || files.stream().anyMatch(f -> f.status().equals("MISSING"))) {
            verdict = "INCOMPLETE";
        } else if (!unplanned.isEmpty() || files.stream().anyMatch(f -> !f.status().equals("ok"))) {
            verdict = "DEVIATES";
        } else {
            verdict = "CONFORMS";
        }
        return new Report(plan, v, run, verdict, files, notInRun, unplanned, notChecked, other);
    }

    private FileCheck check(Workspace ws, PlanVersion v, RunRecord.FileRecord f) throws IOException {
        String display = ws.multiRoot() ? f.root + "/" + f.path : f.path;
        Workspace.Root root = ws.root(f.root);
        Path abs = root == null ? null : root.path().resolve(f.path).normalize();
        boolean exists = abs != null && Files.isRegularFile(abs);
        String current = exists ? PlanValidator.sha256(Files.readAllBytes(abs)) : null;
        List<String> details = new ArrayList<>();
        String status;
        switch (f.kind) {
            case "delete" -> {
                status = exists ? "CHANGED" : "ok";
                if (exists) {
                    details.add("the deleted file exists again");
                }
            }
            case "add" -> {
                status = !exists ? "MISSING" : current.equals(f.writtenHash) ? "ok" : "CHANGED";
                if (!exists) {
                    details.add("the added file is gone");
                } else if (status.equals("CHANGED")) {
                    details.add("edited since the run");
                }
            }
            default -> {
                status = !exists ? "MISSING" : current.equals(f.writtenHash) ? "ok" : "CHANGED";
                if (!exists) {
                    details.add("the file is gone");
                } else if (status.equals("CHANGED")) {
                    String content = ChangedCode.read(abs);
                    details.add("edited since the run");
                    for (String id : f.change.split("\\+")) {
                        PlanChange c = change(v, id);
                        if (c != null && content != null) {
                            details.add(drift(c, content));
                        }
                    }
                }
            }
        }
        return new FileCheck(f.change, f.kind, display, status, details);
    }

    /** For an edited file that changed since the run: is the change's new text still there, has the old one come back? */
    private static String drift(PlanChange c, String content) {
        String after = c.after == null ? "" : c.after.replace("\r\n", "\n");
        String before = c.before == null ? "" : c.before.replace("\r\n", "\n");
        int a = after.isEmpty() ? -1 : content.indexOf(after);
        boolean oldBack = !before.isEmpty() && !after.contains(before) && content.contains(before);
        if (after.isEmpty()) {
            return c.id + ": " + (oldBack ? "the removed lines are back (line " + ChangedCode.lineOf(content, content.indexOf(before)) + ")"
                    : "the removed lines are still gone");
        }
        String s = c.id + ": " + (a >= 0 ? "new text still there (line " + ChangedCode.lineOf(content, a) + ")" : "new text no longer there");
        return oldBack ? s + "; the old text is back (line " + ChangedCode.lineOf(content, content.indexOf(before)) + ")" : s;
    }

    /** Files git sees changed now that it did not see before the run, outside the plan's own files. */
    private void unplanned(RunRecord run, List<String> unplanned, List<String> notChecked) {
        Map<String, Set<String>> mine = new LinkedHashMap<>();
        run.files.forEach(f -> mine.computeIfAbsent(f.root, k -> new LinkedHashSet<>()).add(f.path));
        for (Map.Entry<String, Set<String>> e : mine.entrySet()) {
            Workspace.Root root = snapshot.workspace().root(e.getKey());
            List<String> before = run.gitBefore.get(e.getKey());
            Set<String> nowChanged = root == null ? null : GitStatus.changed(runner, root.path());
            if (before == null || nowChanged == null) {
                notChecked.add(e.getKey() + ": not a git work tree, or git unavailable" + (before == null ? " at the run" : ""));
                continue;
            }
            for (String p : nowChanged) {
                if (!before.contains(p) && !e.getValue().contains(p) && run.createdDirs.stream().noneMatch(d -> p.equals(d + "/"))) {
                    unplanned.add(snapshot.workspace().multiRoot() ? e.getKey() + "/" + p : p);
                }
            }
        }
    }

    /**
     * Checks each finding against the workspace: the file must exist inside it (a bare name only
     * when unique), the line must be in the file, and there must be text. Accepted findings are
     * stored with the review; the rest are returned with the reason.
     */
    public ReviewRecord record(Report report, JsonNode findings) throws IOException {
        ReviewRecord rec = new ReviewRecord();
        rec.plan = report.plan().id;
        rec.version = report.version().number;
        rec.run = report.run().id;
        rec.created = now;
        rec.verdict = report.verdict();
        if (findings != null && findings.isArray()) {
            for (JsonNode n : findings) {
                String file = n.path("file").asText("").trim();
                int line = n.path("line").asInt(0);
                String text = n.path("text").asText("").trim();
                String severity = n.path("severity").asText("minor").trim().toLowerCase();
                String problem = null;
                Path abs = null;
                if (text.isEmpty()) {
                    problem = "no text";
                } else if (!SEVERITIES.contains(severity)) {
                    problem = "severity must be one of " + String.join(", ", SEVERITIES);
                } else {
                    List<String> why = new ArrayList<>();
                    abs = resolve(file, why);
                    if (abs == null) {
                        problem = why.get(0);
                    } else {
                        long count;
                        try (var lines = Files.lines(abs)) {
                            count = lines.count();
                        } catch (IOException | RuntimeException e) {
                            count = 0;
                        }
                        if (line < 1 || line > count) {
                            problem = "line " + line + " is not in " + file + " (" + count + " lines)";
                        }
                    }
                }
                if (problem != null) {
                    rec.rejected.add((file.isEmpty() ? "?" : file) + ":" + line + "  " + problem);
                    continue;
                }
                Finding f = new Finding();
                f.file = display(abs);
                f.line = line;
                f.severity = severity;
                f.text = text;
                rec.findings.add(f);
            }
        }
        Path dir = snapshot.stateDir().resolve("reviews");
        Files.createDirectories(dir);
        Path file = dir.resolve(rec.plan + ".json");
        List<ReviewRecord> all = new ArrayList<>(Files.isRegularFile(file)
                ? JSON.readValue(file.toFile(), new TypeReference<List<ReviewRecord>>() { }) : List.of());
        all.add(rec);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), all);
        new AuditLog(snapshot.stateDir()).record(now, "reviewed", rec.plan, rec.version, rec.run, "review",
                report.run().files.stream().map(f -> f.root + "/" + f.path).toList(),
                rec.verdict + ", " + rec.findings.size() + " findings" + (rec.rejected.isEmpty() ? "" : ", " + rec.rejected.size() + " rejected"));
        return rec;
    }

    /** A finding's file: a workspace-relative path (with its root's name when there are several), or a unique file name. */
    private Path resolve(String file, List<String> why) {
        if (file.isEmpty()) {
            why.add("no file");
            return null;
        }
        String f = file.replace('\\', '/');
        for (Workspace.Root r : snapshot.workspace().roots()) {
            for (String rel : List.of(f, f.startsWith(r.name() + "/") ? f.substring(r.name().length() + 1) : f)) {
                Path p = r.path().resolve(rel).normalize();
                if (p.startsWith(r.path()) && Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        if (!f.contains("/")) {
            List<SourceFile> named = snapshot.graph().files().stream().filter(s -> s.path().endsWith("/" + f) || s.path().equals(f)).toList();
            if (named.size() == 1) {
                return snapshot.workspace().absolute(named.get(0));
            }
            if (named.size() > 1) {
                why.add(named.size() + " files are named " + f + "; give its path");
                return null;
            }
        }
        why.add("no such file in the project");
        return null;
    }

    private String display(Path abs) {
        for (Workspace.Root r : snapshot.workspace().roots()) {
            if (abs.startsWith(r.path())) {
                String rel = r.path().relativize(abs).toString().replace('\\', '/');
                return snapshot.workspace().multiRoot() ? r.name() + "/" + rel : rel;
            }
        }
        return abs.toString();
    }

    private static PlanChange change(PlanVersion v, String id) {
        for (PlanChange c : v.changes) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }
}
