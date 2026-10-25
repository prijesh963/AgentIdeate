package dev.agentideate.plan;

import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.graph.Integrations;
import dev.agentideate.graph.ReverseIndex;
import dev.agentideate.index.JavaFileParser;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a plan version against the code as it is now, so a plan that cites something that is not
 * there never reaches the developer as if it were sound. Every version is checked from the files
 * on disk, not from what an earlier version or the model remembered.
 *
 * <p>Problems block approval: a file outside the project, an edit whose quoted text does not match
 * exactly once, an edit that leaves a Java file unparseable, an added file that exists, a deleted
 * file still referenced elsewhere, evidence that is not in the index, a change a decision ruled
 * out. Warnings do not block: code in another service that depends on what changes but is not part
 * of the plan.
 */
public final class PlanValidator {

    static final int MAX_WARNINGS = 12;
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern PUBLIC_TYPE = Pattern.compile("(?m)^\\s*public\\s+(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*(?:class|interface|enum|record|@interface)\\s+(\\w+)");

    public record Result(List<String> errors, List<String> warnings, Map<String, String> fileHashes) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** A file a change refers to, resolved inside the workspace. */
    record Target(Workspace.Root root, String path, Path absolute) {
        String key() {
            return root.name() + ":" + path;
        }
    }

    private final Workspace workspace;
    private final CodeGraph graph;
    private final JavaFileParser parser = new JavaFileParser();

    public PlanValidator(Workspace workspace, CodeGraph graph) {
        this.workspace = workspace;
        this.graph = graph;
    }

    public Result validate(Plan plan, PlanVersion v) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        if (v.understanding == null || v.understanding.isBlank()) {
            errors.add("The plan needs an understanding: the story in your own words, so the developer can see it was understood.");
        }
        if (v.changes.isEmpty()) {
            errors.add("The plan has no changes.");
        }

        Map<String, List<PlanChange>> editsByFile = new LinkedHashMap<>();
        Map<String, String> contents = new LinkedHashMap<>();
        Set<String> touched = new LinkedHashSet<>();
        Set<String> deleted = new LinkedHashSet<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        for (PlanChange c : v.changes) {
            String id = c.id;
            if (c.kind == null || !List.of("edit", "add", "delete").contains(c.kind)) {
                errors.add(id + ": kind must be edit, add or delete (was '" + c.kind + "').");
                continue;
            }
            Target t = resolve(c.file);
            if (t == null) {
                errors.add(id + ": '" + c.file + "' is not a file inside the project folders.");
                continue;
            }
            c.root = t.root().name();
            c.path = t.path();
            targets.put(t.key(), t);
            boolean exists = Files.isRegularFile(t.absolute());
            String content = null;
            if (exists) {
                try {
                    String raw = Files.readString(t.absolute(), StandardCharsets.UTF_8);
                    hashes.put(t.key(), sha256(raw.getBytes(StandardCharsets.UTF_8)));
                    content = raw.replace("\r\n", "\n");
                    contents.put(t.key(), content);
                } catch (IOException e) {
                    errors.add(id + ": cannot read " + display(t) + ": " + e.getMessage());
                    continue;
                }
            }
            switch (c.kind) {
                case "edit" -> {
                    if (!exists) {
                        errors.add(id + ": " + display(t) + " does not exist; to create it use kind add.");
                        continue;
                    }
                    if (c.before == null || c.before.isEmpty()) {
                        errors.add(id + ": an edit must quote the exact current text it replaces (before).");
                        continue;
                    }
                    if (c.after != null && c.after.replace("\r\n", "\n").equals(c.before.replace("\r\n", "\n"))) {
                        errors.add(id + ": before and after are the same; nothing would change.");
                        continue;
                    }
                    locate(c, t, content, errors);
                    editsByFile.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(c);
                }
                case "add" -> {
                    if (exists) {
                        errors.add(id + ": " + display(t) + " already exists; to change it use kind edit.");
                        continue;
                    }
                    if (c.after == null || c.after.isBlank()) {
                        errors.add(id + ": a new file needs its content (after).");
                        continue;
                    }
                    if (touched.contains(t.key())) {
                        errors.add(id + ": " + display(t) + " is added twice.");
                    }
                    c.startLine = 1;
                    c.endLine = (int) c.after.lines().count();
                    if (t.path().endsWith(".java")) {
                        checkNewJava(c, t, errors);
                    }
                }
                default -> { // delete
                    if (!exists) {
                        errors.add(id + ": " + display(t) + " does not exist, so it cannot be deleted.");
                        continue;
                    }
                    deleted.add(t.key());
                    c.startLine = 1;
                    c.endLine = (int) content.lines().count();
                }
            }
            touched.add(t.key());
            checkEvidence(c, errors);
        }

        // Several edits to one file: they must not overlap, and the file must still parse with all applied.
        for (Map.Entry<String, List<PlanChange>> e : editsByFile.entrySet()) {
            List<PlanChange> edits = new ArrayList<>(e.getValue());
            edits.removeIf(c -> c.startLine <= 0);
            edits.sort((a, b) -> Integer.compare(a.startLine, b.startLine));
            for (int i = 1; i < edits.size(); i++) {
                if (edits.get(i).startLine <= edits.get(i - 1).endLine) {
                    errors.add(edits.get(i).id + ": overlaps " + edits.get(i - 1).id + " in " + display(targets.get(e.getKey()))
                            + " (lines " + edits.get(i - 1).startLine + "-" + edits.get(i - 1).endLine + "); merge them into one edit.");
                }
            }
            if (deleted.contains(e.getKey())) {
                errors.add(display(targets.get(e.getKey())) + " is both edited and deleted.");
            }
            String original = contents.get(e.getKey());
            if (e.getKey().endsWith(".java") && original != null && edits.size() == e.getValue().size()
                    && parser.parse(original).error() == null) {
                String updated = applyEdits(original, edits);
                String err = parser.parse(updated).error();
                if (err != null) {
                    errors.add(String.join("+", edits.stream().map(c -> c.id).toList()) + ": after this change "
                            + display(targets.get(e.getKey())) + " no longer parses: " + err);
                }
            }
        }

        checkDecisions(plan, v, errors);
        checkDeletedReferences(v, deleted, touched, contents, errors, warnings);
        checkOtherServices(v, touched, warnings);
        return new Result(errors, warnings, hashes);
    }

    /** Finds an edit's quoted text: exactly once, or a problem that says how to fix the quote. */
    private void locate(PlanChange c, Target t, String content, List<String> errors) {
        String before = c.before.replace("\r\n", "\n");
        List<Integer> at = new ArrayList<>();
        for (int i = content.indexOf(before); i >= 0; i = content.indexOf(before, i + 1)) {
            at.add(i);
        }
        if (at.size() == 1) {
            c.startLine = lineOf(content, at.get(0));
            c.endLine = c.startLine + (int) before.chars().filter(ch -> ch == '\n').count()
                    - (before.endsWith("\n") ? 1 : 0);
            return;
        }
        c.startLine = 0;
        c.endLine = 0;
        if (at.size() > 1) {
            List<String> lines = new ArrayList<>();
            at.stream().limit(5).forEach(i -> lines.add(String.valueOf(lineOf(content, i))));
            errors.add(c.id + ": the quoted text matches " + at.size() + " places in " + display(t) + " (lines "
                    + String.join(", ", lines) + "); quote more surrounding lines so it matches once.");
            return;
        }
        int loose = looseMatch(content, before);
        errors.add(c.id + ": the quoted text is not in " + display(t)
                + (loose > 0 ? "; similar text with different spacing is at line " + loose + ", quote it exactly as it is in the file."
                : "; quote the current text exactly (the file may have changed since you read it)."));
    }

    /**
     * Line where the quote matches if all whitespace is ignored, or 0. Only a hint for fixing the
     * quote: an edit itself always needs the exact text.
     */
    static int looseMatch(String content, String before) {
        String needle = before.replaceAll("\\s+", "");
        if (needle.length() < 8) {
            return 0;
        }
        StringBuilder flat = new StringBuilder();
        List<Integer> lineAt = new ArrayList<>();
        int line = 1;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\n') {
                line++;
            } else if (!Character.isWhitespace(ch)) {
                flat.append(ch);
                lineAt.add(line);
            }
        }
        int idx = flat.indexOf(needle);
        return idx < 0 ? 0 : lineAt.get(idx);
    }

    static String applyEdits(String content, List<PlanChange> editsInOrder) {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (PlanChange c : editsInOrder) {
            String before = c.before.replace("\r\n", "\n");
            int at = content.indexOf(before, pos);
            if (at < 0) {
                return content;
            }
            sb.append(content, pos, at).append(c.after == null ? "" : c.after.replace("\r\n", "\n"));
            pos = at + before.length();
        }
        return sb.append(content.substring(pos)).toString();
    }

    private void checkNewJava(PlanChange c, Target t, List<String> errors) {
        String err = parser.parse(c.after).error();
        if (err != null) {
            errors.add(c.id + ": the new file " + display(t) + " does not parse: " + err);
            return;
        }
        Matcher pkg = PACKAGE.matcher(c.after);
        String dir = t.path().contains("/") ? t.path().substring(0, t.path().lastIndexOf('/')) : "";
        if (pkg.find()) {
            String expected = pkg.group(1).replace('.', '/');
            if (!dir.endsWith(expected)) {
                errors.add(c.id + ": package " + pkg.group(1) + " does not match the folder " + dir + "/.");
            }
        }
        Matcher type = PUBLIC_TYPE.matcher(c.after);
        String fileName = t.path().substring(t.path().lastIndexOf('/') + 1).replace(".java", "");
        if (type.find() && !type.group(1).equals(fileName)) {
            errors.add(c.id + ": public type " + type.group(1) + " must be in " + type.group(1) + ".java, not " + fileName + ".java.");
        }
    }

    /** Evidence must name something in the index: a class, Class#method, a topic or a table. */
    private void checkEvidence(PlanChange c, List<String> errors) {
        for (String e : c.evidence) {
            if (e == null || e.isBlank()) {
                continue;
            }
            if (!exists(e.trim())) {
                errors.add(c.id + ": evidence '" + e + "' is not in the index; cite a class, Class#method, topic or table "
                        + "as locate or impact_of names it.");
            }
        }
    }

    boolean exists(String ref) {
        String type = ref;
        String method = null;
        int hash = ref.indexOf('#');
        if (hash > 0) {
            type = ref.substring(0, hash);
            method = ref.substring(hash + 1).replaceAll("\\(.*", "");
        }
        List<TypeNode> nodes = new ArrayList<>(graph.byFqn(type));
        if (nodes.isEmpty()) {
            nodes.addAll(graph.bySimpleName(type));
        }
        if (!nodes.isEmpty()) {
            if (method == null) {
                return true;
            }
            for (TypeNode n : nodes) {
                if (n.method(method) != null || !graph.reverse().callersOf(n, method).isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        Integrations integ = graph.integrations();
        return integ.messages().stream().anyMatch(m -> m.channel().equals(ref))
                || integ.entities().stream().anyMatch(en -> en.name().equalsIgnoreCase(ref));
    }

    private void checkDecisions(Plan plan, PlanVersion v, List<String> errors) {
        for (Decision d : plan.decisions) {
            for (String glob : d.forbid) {
                PathMatcher m;
                try {
                    m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                } catch (RuntimeException e) {
                    errors.add("Decision " + d.id + " has an unreadable path pattern '" + glob + "'.");
                    continue;
                }
                for (PlanChange c : v.changes) {
                    if (c.path == null) {
                        continue;
                    }
                    if (m.matches(Path.of(c.path)) || m.matches(Path.of(c.root + "/" + c.path))
                            || m.matches(Path.of(c.path.substring(c.path.lastIndexOf('/') + 1)))) {
                        errors.add(c.id + ": changes " + c.path + ", which decision " + d.id + " rules out: " + d.text);
                    }
                }
            }
        }
    }

    /** A deleted file's types must not be left referenced by files the plan does not change. */
    private void checkDeletedReferences(PlanVersion v, Set<String> deleted, Set<String> touched, Map<String, String> contents,
                                        List<String> errors, List<String> warnings) {
        ReverseIndex rev = graph.reverse();
        for (String key : deleted) {
            for (TypeNode t : typesIn(key)) {
                Set<String> refs = new LinkedHashSet<>();
                rev.callersOf(t).forEach(r -> refs.add(r.owner().file().key()));
                rev.injectorsOf(t).forEach(i -> refs.add(i.owner().file().key()));
                for (SourceFile f : graph.files()) {
                    if (f.imports().contains(t.fqn())) {
                        refs.add(f.key());
                    }
                }
                refs.remove(key);
                for (String ref : refs) {
                    if (deleted.contains(ref)) {
                        continue;
                    }
                    String where = ref.substring(ref.indexOf(':') + 1);
                    if (!touched.contains(ref)) {
                        errors.add("Deleting " + t.name() + " leaves it referenced by " + where
                                + "; change or delete that file in this plan too, or keep " + t.name() + ".");
                    } else {
                        warnings.add(where + " is changed in this plan and refers to " + t.name()
                                + ", which the plan deletes; make sure the change removes that reference.");
                    }
                }
            }
        }
    }

    /**
     * Code in another service that depends on what the plan changes, but that the plan leaves alone:
     * a payload, an injected type, a topic's consumers. Worth a look before approving; not a problem
     * in itself, because the change may be compatible.
     */
    /**
     * Code in other services that depends on what a version changes but that the version leaves
     * alone; used again by review, against the code as implemented. The version's changes must
     * already be resolved (root and path set), as validation leaves them.
     */
    public List<String> otherServiceEffects(PlanVersion v) {
        Set<String> touched = new LinkedHashSet<>();
        for (PlanChange c : v.changes) {
            if (c.root != null && c.path != null) {
                touched.add(c.root + ":" + c.path);
            }
        }
        List<String> out = new ArrayList<>();
        checkOtherServices(v, touched, out);
        return out;
    }

    private void checkOtherServices(PlanVersion v, Set<String> touched, List<String> warnings) {
        ReverseIndex rev = graph.reverse();
        Integrations integ = graph.integrations();
        Set<String> touchedModules = new LinkedHashSet<>();
        for (String key : touched) {
            for (SourceFile f : graph.files()) {
                if (f.key().equals(key)) {
                    touchedModules.add(graph.moduleKey(f));
                }
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (PlanChange c : v.changes) {
            if (c.path == null || c.kind.equals("add")) {
                continue;
            }
            for (TypeNode t : typesIn(c.root + ":" + c.path)) {
                String home = graph.moduleKey(t.file());
                Set<TypeNode> users = new LinkedHashSet<>();
                rev.callersOf(t).forEach(r -> users.add(r.owner()));
                rev.injectorsOf(t).forEach(i -> users.add(i.owner()));
                for (TypeNode u : users) {
                    String m = graph.moduleKey(u.file());
                    if (!m.equals(home) && !touchedModules.contains(m)) {
                        out.add(c.id + " changes " + t.name() + ", used by " + u.name() + " in " + m
                                + ", which this plan does not change.");
                    }
                }
                for (var md : t.decl().methods()) {
                    for (Integrations.Message msg : integ.messagesIn(t, md)) {
                        if (!msg.producer()) {
                            continue;
                        }
                        List<String> consumers = new ArrayList<>();
                        for (Integrations.Message other : integ.counterparts(msg)) {
                            String m = graph.moduleKey(other.owner().file());
                            if (!touchedModules.contains(m)) {
                                consumers.add(m + " (" + other.owner().name() + "#" + other.method().name() + ")");
                            }
                        }
                        if (!consumers.isEmpty()) {
                            out.add(c.id + " changes " + t.name() + ", which sends " + msg.system() + " " + msg.channel()
                                    + " to " + String.join(", ", consumers) + ", not in this plan: keep the message compatible.");
                        }
                    }
                }
            }
        }
        out.stream().limit(MAX_WARNINGS).forEach(warnings::add);
        if (out.size() > MAX_WARNINGS) {
            warnings.add("+" + (out.size() - MAX_WARNINGS) + " more cross-service dependencies; impact_of lists them all.");
        }
    }

    private List<TypeNode> typesIn(String fileKey) {
        List<TypeNode> out = new ArrayList<>();
        for (TypeNode n : graph.types()) {
            if (n.file().key().equals(fileKey)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Resolves a file as an agent writes it: root-relative ({@code order-service/src/...}),
     * prefixed by the root name in a multi-repo workspace, or absolute inside a root. Anything that
     * leaves the root, by {@code ..} or a link, does not resolve.
     */
    Target resolve(String file) {
        if (file == null || file.isBlank()) {
            return null;
        }
        String f = file.trim().replace('\\', '/');
        List<Target> candidates = new ArrayList<>();
        for (Workspace.Root r : workspace.roots()) {
            Path p = Path.of(f).isAbsolute() ? Path.of(f) : r.path().resolve(f);
            candidates.add(target(r, p));
            if (workspace.multiRoot() && f.startsWith(r.name() + "/")) {
                candidates.add(target(r, r.path().resolve(f.substring(r.name().length() + 1))));
            }
        }
        Target fallback = null;
        for (Target t : candidates) {
            if (t == null) {
                continue;
            }
            if (Files.exists(t.absolute())) {
                return t;
            }
            if (fallback == null) {
                fallback = t;
            }
        }
        return fallback;
    }

    private static Target target(Workspace.Root r, Path p) {
        Path n = p.toAbsolutePath().normalize();
        if (!n.startsWith(r.path()) || n.equals(r.path())) {
            return null;
        }
        try {
            if (Files.exists(n) && !n.toRealPath().startsWith(r.path())) {
                return null; // a link out of the project
            }
        } catch (IOException e) {
            return null;
        }
        return new Target(r, r.path().relativize(n).toString().replace('\\', '/'), n);
    }

    String display(Target t) {
        return workspace.multiRoot() ? t.root().name() + "/" + t.path() : t.path();
    }

    private static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
