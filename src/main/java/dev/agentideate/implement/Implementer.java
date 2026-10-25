package dev.agentideate.implement;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentideate.exec.CommandRunner;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanChange;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.plan.PlanValidator;
import dev.agentideate.plan.PlanVersion;
import dev.agentideate.tools.LogDigest;
import dev.agentideate.workspace.Workspace;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Workflow 3: writes an approved plan into the project, exactly as approved, or not at all.
 *
 * <ol>
 *   <li>Gate: the plan is approved, its approval matches the latest version's content, and every
 *       file it touches is byte-for-byte what it was at approval.</li>
 *   <li>Pre-flight: the version re-validates against the code; files are UTF-8; every new content
 *       is computed in memory before anything is written.</li>
 *   <li>Apply: originals are backed up, each file is written atomically, and any failure restores
 *       every file already written.</li>
 *   <li>Verify: each file is re-read and compared with what was meant; git status before and after
 *       shows anything that changed outside the plan; the changed modules are compiled, and a plan
 *       whose code does not compile is rolled back.</li>
 * </ol>
 *
 * Every run is recorded (with its backups) and audited, and can be undone.
 */
public final class Implementer {

    public static final Duration DEFAULT_COMPILE_TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** What happened, for the answer. {@code refused} runs wrote nothing. */
    public record Outcome(RunRecord run, List<String> problems, List<String> compileDigest) {
        public boolean applied() {
            return run != null && "applied".equals(run.status);
        }
    }

    /** One file to write, with its original and new content (normalized to the file's line endings). */
    private record Target(Workspace.Root root, String path, Path absolute, String kind, String original, String updated,
                          List<PlanChange> changes) {
        String key() {
            return root.name() + ":" + path;
        }
    }

    private final WorkspaceService.Snapshot snapshot;
    private final CommandRunner runner;
    private final long now;
    private final Path runsDir;
    private final AuditLog audit;

    public Implementer(WorkspaceService.Snapshot snapshot, CommandRunner runner, long now) {
        this.snapshot = snapshot;
        this.runner = runner;
        this.now = now;
        this.runsDir = snapshot.stateDir().resolve("runs");
        this.audit = new AuditLog(snapshot.stateDir());
    }

    /** Why this plan cannot be implemented now, or {@code null}. Checks the gate and the pre-flight; writes nothing. */
    public String blocker(Plan plan) throws IOException {
        PlanVersion v = plan.latest();
        if (v == null || !plan.approved() || plan.approvedVersion == null || plan.approvedVersion != v.number) {
            return plan.id + " has no approved latest version; the developer approves it first (plan_approve).";
        }
        if (plan.implemented()) {
            return plan.id + " v" + v.number + " is already implemented (run " + plan.implementedRun + ").";
        }
        if (!v.contentHash.equals(plan.approvedHash)) {
            return plan.id + " v" + v.number + " is not what was approved; it needs a new approval.";
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : plan.approvedFileHashes.entrySet()) {
            Path p = absolute(e.getKey());
            if (p == null || !Files.isRegularFile(p) || !sha256(Files.readAllBytes(p)).equals(e.getValue())) {
                changed.add(e.getKey().substring(e.getKey().indexOf(':') + 1));
            }
        }
        if (!changed.isEmpty()) {
            return "These files changed after the plan was approved, so it no longer describes them: " + String.join(", ", changed)
                    + ". Nothing was written. Revise the plan (plan_submit) and approve it again.";
        }
        PlanValidator.Result r = new PlanValidator(snapshot.workspace(), snapshot.graph()).validate(plan, v);
        if (!r.valid()) {
            return "The approved version no longer validates against the code:\n  " + String.join("\n  ", r.errors());
        }
        return null;
    }

    /**
     * Applies the approved latest version.
     *
     * @param via              how the developer started it (chat form, tool confirmation, terminal)
     * @param compile          run the compile check
     * @param keepOnCompileErr keep the changes even if the plan's code does not compile
     */
    public Outcome implement(Plan plan, String via, boolean compile, boolean keepOnCompileErr, Duration compileTimeout)
            throws IOException {
        String blocker = blocker(plan);
        if (blocker != null) {
            return new Outcome(null, List.of(blocker), List.of());
        }
        PlanVersion v = plan.latest();
        new PlanValidator(snapshot.workspace(), snapshot.graph()).validate(plan, v); // fills root/path/lines from today's files

        // Compute every file's new content before writing anything.
        List<Target> targets;
        try {
            targets = targets(v);
        } catch (IOException e) {
            return new Outcome(null, List.of(e.getMessage() + " Nothing was written."), List.of());
        }

        Map<String, Set<String>> gitBefore = gitStatus(targets);
        RunRecord run = new RunRecord();
        run.id = nextRunId();
        run.plan = plan.id;
        run.version = v.number;
        run.created = now;
        run.via = via;
        Path runDir = runsDir.resolve(run.id);
        Files.createDirectories(runDir.resolve("backup"));

        List<String> problems = new ArrayList<>();
        try {
            write(targets, run, runDir);
            run.status = "applied";
        } catch (IOException | RuntimeException e) {
            List<String> undo = restore(run, runDir);
            run.status = "rolled_back";
            run.note = "writing failed (" + e.getMessage() + "); every file was restored" + (undo.isEmpty() ? "" : ": " + undo);
            problems.add("Writing failed: " + e.getMessage() + ". Every file already written was restored; the project is as it was.");
            save(run, runDir);
            audit.record(now, "rolled_back", plan.id, v.number, run.id, via, paths(run), run.note);
            return new Outcome(run, problems, List.of());
        }
        audit.record(now, "applied", plan.id, v.number, run.id, via, paths(run), null);

        verify(targets, run);
        outsidePlan(targets, gitBefore, run);

        List<String> digest = List.of();
        if (compile) {
            digest = compile(targets, run, compileTimeout);
            if ("failed".equals(run.compileStatus) && !keepOnCompileErr) {
                List<String> undo = restore(run, runDir);
                run.status = "rolled_back";
                run.note = "the plan's code does not compile; every file was restored" + (undo.isEmpty() ? "" : ": " + undo);
                problems.add("The changed code does not compile, so the run was rolled back; the project is as it was.");
                audit.record(now, "rolled_back", plan.id, v.number, run.id, via, paths(run), "compile errors");
            }
        } else {
            run.compileStatus = "skipped";
        }
        if ("applied".equals(run.status)) {
            plan.status = "implemented";
            plan.implementedRun = run.id;
            plan.implementedAt = now;
            plan.updated = now;
            new PlanStore(snapshot.stateDir()).save(plan);
        }
        save(run, runDir);
        return new Outcome(run, problems, digest);
    }

    private List<Target> targets(PlanVersion v) throws IOException {
        Map<String, List<PlanChange>> byFile = new LinkedHashMap<>();
        for (PlanChange c : v.changes) {
            byFile.computeIfAbsent(c.root + ":" + c.path, k -> new ArrayList<>()).add(c);
        }
        List<Target> out = new ArrayList<>();
        for (Map.Entry<String, List<PlanChange>> e : byFile.entrySet()) {
            Path abs = absolute(e.getKey());
            Workspace.Root root = snapshot.workspace().root(e.getKey().substring(0, e.getKey().indexOf(':')));
            String path = e.getKey().substring(e.getKey().indexOf(':') + 1);
            PlanChange first = e.getValue().get(0);
            switch (first.kind) {
                case "add" -> out.add(new Target(root, path, abs, "add", null, first.after, e.getValue()));
                case "delete" -> out.add(new Target(root, path, abs, "delete", utf8(abs), null, e.getValue()));
                default -> {
                    String raw = utf8(abs);
                    boolean crlf = raw.contains("\r\n");
                    String normalized = raw.replace("\r\n", "\n");
                    List<PlanChange> edits = new ArrayList<>(e.getValue());
                    edits.sort(Comparator.comparingInt(c -> c.startLine));
                    for (PlanChange c : edits) {
                        String before = c.before.replace("\r\n", "\n");
                        int at = normalized.indexOf(before);
                        if (at < 0 || normalized.indexOf(before, at + 1) >= 0) {
                            throw new IOException(c.id + ": the quoted text no longer matches exactly once in " + path + ".");
                        }
                    }
                    String updated = applyInOrder(normalized, edits);
                    out.add(new Target(root, path, abs, "edit", raw, crlf ? updated.replace("\n", "\r\n") : updated, e.getValue()));
                }
            }
        }
        return out;
    }

    private static String applyInOrder(String content, List<PlanChange> edits) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (PlanChange c : edits) {
            String before = c.before.replace("\r\n", "\n");
            int at = content.indexOf(before, pos);
            sb.append(content, pos, at).append(c.after == null ? "" : c.after.replace("\r\n", "\n"));
            pos = at + before.length();
        }
        return sb.append(content.substring(pos)).toString();
    }

    private void write(List<Target> targets, RunRecord run, Path runDir) throws IOException {
        for (Target t : targets) {
            RunRecord.FileRecord f = new RunRecord.FileRecord();
            f.change = String.join("+", t.changes().stream().map(c -> c.id).toList());
            f.kind = t.kind();
            f.root = t.root().name();
            f.path = t.path();
            if (t.original() != null) {
                byte[] orig = Files.readAllBytes(t.absolute());
                f.originalHash = sha256(orig);
                Path backup = runDir.resolve("backup").resolve(t.root().name()).resolve(t.path());
                Files.createDirectories(backup.getParent());
                Files.write(backup, orig);
                f.backup = runDir.relativize(backup).toString().replace('\\', '/');
            }
            run.files.add(f); // recorded before the write, so a failure part-way still knows what to undo
            switch (t.kind()) {
                case "delete" -> Files.delete(t.absolute());
                case "add" -> {
                    if (Files.exists(t.absolute())) {
                        throw new IOException(t.path() + " appeared after the plan was checked");
                    }
                    createParents(t.absolute(), t.root().path(), run);
                    byte[] bytes = t.updated().getBytes(StandardCharsets.UTF_8);
                    atomicWrite(t.absolute(), bytes);
                    f.writtenHash = sha256(bytes);
                }
                default -> {
                    byte[] bytes = t.updated().getBytes(StandardCharsets.UTF_8);
                    atomicWrite(t.absolute(), bytes);
                    f.writtenHash = sha256(bytes);
                }
            }
        }
    }

    private static void createParents(Path file, Path root, RunRecord run) throws IOException {
        List<Path> missing = new ArrayList<>();
        for (Path d = file.getParent(); d != null && d.startsWith(root) && !Files.exists(d); d = d.getParent()) {
            missing.add(0, d);
        }
        for (Path d : missing) {
            Files.createDirectory(d);
            run.createdDirs.add(root.relativize(d).toString().replace('\\', '/'));
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".agentideate.tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Re-reads every file: an edit's new text is there, an added file is exactly its content, a deleted file is gone. */
    private void verify(List<Target> targets, RunRecord run) throws IOException {
        for (Target t : targets) {
            String id = String.join("+", t.changes().stream().map(c -> c.id).toList());
            switch (t.kind()) {
                case "delete" -> run.verify.add((Files.exists(t.absolute()) ? "FAIL " : "ok   ") + id + " deleted " + t.path());
                default -> {
                    boolean same = Files.isRegularFile(t.absolute())
                            && Files.readString(t.absolute(), StandardCharsets.UTF_8).equals(t.updated());
                    boolean afterPresent = true;
                    if (same && t.kind().equals("edit")) {
                        String written = t.updated().replace("\r\n", "\n");
                        for (PlanChange c : t.changes()) {
                            if (c.after != null && !c.after.isEmpty() && !written.contains(c.after.replace("\r\n", "\n"))) {
                                afterPresent = false;
                            }
                        }
                    }
                    run.verify.add((same && afterPresent ? "ok   " : "FAIL ") + id + " " + (t.kind().equals("add") ? "added " : "edited ")
                            + t.path() + (t.kind().equals("edit") ? " from line " + t.changes().stream().mapToInt(c -> c.startLine).min().orElse(0) : ""));
                }
            }
        }
    }

    private Map<String, Set<String>> gitStatus(List<Target> targets) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Target t : targets) {
            if (!out.containsKey(t.root().name())) {
                out.put(t.root().name(), GitStatus.changed(runner, t.root().path())); // null: not checked, and said so
            }
        }
        return out;
    }

    /** Files git sees changed after the run that were not changed before it and are not the plan's own. */
    private void outsidePlan(List<Target> targets, Map<String, Set<String>> before, RunRecord run) {
        Map<String, Set<String>> after = gitStatus(targets);
        for (Map.Entry<String, Set<String>> e : after.entrySet()) {
            Set<String> b = before.get(e.getKey());
            if (b == null || e.getValue() == null) {
                run.outsidePlan.add("(" + e.getKey() + ": not a git work tree, or git unavailable: not checked)");
                continue;
            }
            Set<String> mine = new LinkedHashSet<>();
            targets.stream().filter(t -> t.root().name().equals(e.getKey())).forEach(t -> mine.add(t.path()));
            for (String p : e.getValue()) {
                if (!b.contains(p) && !mine.contains(p) && run.createdDirs.stream().noneMatch(d -> p.equals(d + "/"))) {
                    run.outsidePlan.add(e.getKey() + "/" + p);
                }
            }
        }
    }

    private List<String> compile(List<Target> targets, RunRecord run, Duration timeout) {
        Set<Path> modules = new LinkedHashSet<>();
        for (Target t : targets) {
            Path dir = moduleDir(t);
            if (dir != null) {
                modules.add(dir);
            }
        }
        List<String> digest = new ArrayList<>();
        String status = "passed";
        List<String> commands = new ArrayList<>();
        LogDigest logDigest = new LogDigest(cls -> null, this::display);
        for (Path m : modules) {
            Workspace.Root root = rootOf(m);
            CompileCheck.Outcome o = CompileCheck.run(runner, root.path(), m, timeout);
            commands.add(o.command());
            switch (o.status()) {
                case "passed" -> {
                }
                case "failed" -> {
                    if (CompileCheck.hasCompileErrors(o.output())) {
                        status = "failed";
                    } else if (!status.equals("failed")) {
                        status = "not-run"; // the build failed for another reason (often dependencies); not the plan's code
                    }
                    digest.addAll(logDigest.digest(o.output()));
                }
                case "timeout" -> {
                    if (!status.equals("failed")) {
                        status = "timeout";
                    }
                    digest.add("compile of " + m.getFileName() + " ran over " + timeout.toMinutes() + " minutes and was stopped");
                }
                default -> {
                    if (!status.equals("failed")) {
                        status = "not-run";
                    }
                    digest.add("could not run the compile check for " + m.getFileName() + ": " + o.output());
                }
            }
        }
        if (modules.isEmpty()) {
            status = "not-run";
            digest.add("no build module found for the changed files");
        }
        run.compileStatus = status;
        run.compileCommand = String.join(" ; ", commands);
        run.compileDigest = new ArrayList<>(digest);
        return digest;
    }

    private Path moduleDir(Target t) {
        Path root = t.root().path();
        for (Path d = t.absolute().getParent(); d != null && d.startsWith(root); d = d.getParent()) {
            if (Files.exists(d.resolve("build.gradle")) || Files.exists(d.resolve("build.gradle.kts")) || Files.exists(d.resolve("pom.xml"))) {
                return d;
            }
        }
        return null;
    }

    private Workspace.Root rootOf(Path p) {
        for (Workspace.Root r : snapshot.workspace().roots()) {
            if (p.startsWith(r.path())) {
                return r;
            }
        }
        return snapshot.workspace().roots().get(0);
    }

    private String display(String absolute) {
        Path p = Path.of(absolute).toAbsolutePath().normalize();
        for (Workspace.Root r : snapshot.workspace().roots()) {
            if (p.startsWith(r.path())) {
                String rel = r.path().relativize(p).toString().replace('\\', '/');
                return snapshot.workspace().multiRoot() ? r.name() + "/" + rel : rel;
            }
        }
        return absolute;
    }

    /**
     * Undoes a run: restores each original from its backup, removes added files and the folders
     * created for them. A file changed since the run (its hash is no longer what the run wrote) is
     * left alone and reported, so an undo never destroys later work.
     *
     * @return files that could not be restored, with the reason
     */
    public List<String> undo(RunRecord run) throws IOException {
        Path runDir = runsDir.resolve(run.id);
        List<String> skipped = restore(run, runDir);
        run.status = "rolled_back";
        run.note = "undone" + (skipped.isEmpty() ? "" : "; left alone: " + skipped);
        save(run, runDir);
        audit.record(now, "undone", run.plan, run.version, run.id, "undo", paths(run), skipped.isEmpty() ? null : String.join("; ", skipped));
        Plan plan = new PlanStore(snapshot.stateDir()).get(run.plan);
        if (plan != null && run.id.equals(plan.implementedRun)) {
            plan.status = "approved";
            plan.implementedRun = null;
            plan.updated = now;
            new PlanStore(snapshot.stateDir()).save(plan);
        }
        return skipped;
    }

    private List<String> restore(RunRecord run, Path runDir) throws IOException {
        List<String> skipped = new ArrayList<>();
        List<RunRecord.FileRecord> reversed = new ArrayList<>(run.files);
        Collections.reverse(reversed);
        for (RunRecord.FileRecord f : reversed) {
            Path abs = absolute(f.root + ":" + f.path);
            if (abs == null) {
                skipped.add(f.path + " (root not in this workspace)");
                continue;
            }
            boolean exists = Files.isRegularFile(abs);
            String current = exists ? sha256(Files.readAllBytes(abs)) : null;
            switch (f.kind) {
                case "add" -> {
                    if (!exists) {
                        continue; // never written, or already removed
                    }
                    if (f.writtenHash != null && !current.equals(f.writtenHash)) {
                        skipped.add(f.path + " (changed since the run)");
                        continue;
                    }
                    Files.delete(abs);
                }
                case "delete" -> {
                    if (exists) {
                        if (!current.equals(f.originalHash)) {
                            skipped.add(f.path + " (re-created and changed since the run)");
                        }
                        continue;
                    }
                    Files.createDirectories(abs.getParent());
                    Files.copy(runDir.resolve(f.backup), abs);
                }
                default -> {
                    if (f.writtenHash == null || (exists && current.equals(f.originalHash))) {
                        continue; // the write never happened
                    }
                    if (!exists || !current.equals(f.writtenHash)) {
                        skipped.add(f.path + " (changed since the run)");
                        continue;
                    }
                    atomicWrite(abs, Files.readAllBytes(runDir.resolve(f.backup)));
                }
            }
        }
        List<String> dirs = new ArrayList<>(run.createdDirs);
        Collections.reverse(dirs);
        for (String d : dirs) {
            for (Workspace.Root r : snapshot.workspace().roots()) {
                Path p = r.path().resolve(d);
                try (Stream<Path> s = Files.isDirectory(p) ? Files.list(p) : Stream.empty()) {
                    if (Files.isDirectory(p) && s.findAny().isEmpty()) {
                        Files.delete(p);
                    }
                }
            }
        }
        return skipped;
    }

    public RunRecord load(String runId) throws IOException {
        Path f = runsDir.resolve(runId).resolve("run.json");
        if (runId == null || !runId.matches("r\\d+") || !Files.isRegularFile(f)) {
            return null;
        }
        return JSON.readValue(f.toFile(), RunRecord.class);
    }

    private void save(RunRecord run, Path runDir) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("run.json").toFile(), run);
    }

    private String nextRunId() throws IOException {
        int max = 0;
        if (Files.isDirectory(runsDir)) {
            try (Stream<Path> s = Files.list(runsDir)) {
                for (Path p : s.toList()) {
                    String n = p.getFileName().toString();
                    if (n.matches("r\\d+")) {
                        max = Math.max(max, Integer.parseInt(n.substring(1)));
                    }
                }
            }
        }
        return "r" + (max + 1);
    }

    private Path absolute(String key) {
        int colon = key.indexOf(':');
        Workspace.Root r = snapshot.workspace().root(key.substring(0, colon));
        if (r == null) {
            return null;
        }
        Path p = r.path().resolve(key.substring(colon + 1)).normalize();
        return p.startsWith(r.path()) ? p : null;
    }

    private static List<String> paths(RunRecord run) {
        return run.files.stream().map(f -> f.root + "/" + f.path).toList();
    }

    /** Reads a file as strict UTF-8: a file in another encoding is refused rather than rewritten wrongly. */
    private static String utf8(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(p.getFileName() + " is not UTF-8; agentideate will not rewrite it.");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
