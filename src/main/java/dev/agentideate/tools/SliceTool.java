package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.Call;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.graph.Integrations;
import dev.agentideate.graph.ReverseIndex;
import dev.agentideate.index.Ann;
import dev.agentideate.index.FieldDecl;
import dev.agentideate.index.InjectionPoint;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The context an agent needs for a task, instead of whole files: the classes the task is about
 * and their immediate collaborators as signature skeletons (annotations, injections, method
 * signatures with line numbers and what each calls), the routes and consumers that reach them, and
 * the source of only the few methods that matter most.
 */
final class SliceTool implements Tool {

    static final int DEFAULT_TYPES = 6;
    static final int MAX_TYPES = 10;
    static final int MAX_SEEDS = 4;
    static final int MAX_BODIES = 3;
    static final int MAX_BODY_LINES = 40;
    static final int MAX_METHODS_PER_TYPE = 15;

    @Override
    public String name() {
        return "slice_for_task";
    }

    @Override
    public String description() {
        return "Context for a task in one call: relevant classes and collaborators as signatures with line "
                + "numbers, entry points, and source of the key methods. Use instead of opening whole files.";
    }

    @Override
    public int budgetChars() {
        return 14_000;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("task").put("type", "string").put("description", "The task or user story, in words");
        p.putObject("max_types").put("type", "integer").put("description", "Classes to include (default 6, max 10)");
        s.putArray("required").add("task");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        String task = args.required("task");
        int maxTypes = args.integer("max_types", DEFAULT_TYPES, 1, MAX_TYPES);
        List<String> words = LocateTool.queryTokens(task);
        if (words.isEmpty()) {
            throw new ToolException("The task has no searchable words: '" + task + "'.");
        }
        // a task is phrased loosely, so one matching word qualifies a candidate; ranking does the rest
        List<LocateTool.Hit> hits = LocateTool.rank(LocateTool.candidates(graph), words, null, 0.0);

        // Seeds: the best-matching classes, and the classes of the best-matching methods/routes/topics.
        // Logic (beans) and data (entities) outrank plain classes and records whose name merely matches.
        Integrations integ = graph.integrations();
        // insertion-ordered (TypeNode compares by identity): equal scores keep the hits' deterministic order
        Map<TypeNode, Double> scored = new LinkedHashMap<>();
        Map<MethodDecl, Double> methodScores = new IdentityHashMap<>();
        Map<MethodDecl, TypeNode> methodOwner = new IdentityHashMap<>();
        for (LocateTool.Hit h : hits) {
            TypeNode t = h.c().type();
            if (t == null || t.file().isTest()) {
                continue;
            }
            double weight = graph.beanKind(t) != null ? 1.0 : integ.entityOf(t) != null ? 0.8 : 0.5;
            scored.merge(t, h.score() * weight, Math::max);
            if (h.c().method() != null) {
                methodScores.merge(h.c().method(), h.score(), Math::max);
                methodOwner.putIfAbsent(h.c().method(), t);
            }
        }
        Map<TypeNode, Double> seeds = new LinkedHashMap<>();
        scored.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(MAX_SEEDS)
                .forEach(e -> seeds.put(e.getKey(), e.getValue()));
        if (seeds.isEmpty()) {
            out.line("Not found: nothing in the index matches the words of this task (" + String.join(", ", words) + ").");
            out.line("This may be new functionality. repo_overview shows where similar code lives; locate searches by name.");
            return;
        }

        // Strong seeds (close to the best match) first, then their collaborators, then weaker seeds
        // only while there is room: a word shared by chance must not crowd out the core's data.
        Set<TypeNode> slice = Collections.newSetFromMap(new IdentityHashMap<>());
        List<TypeNode> ordered = new ArrayList<>();
        double top = seeds.values().iterator().next();
        List<TypeNode> strong = new ArrayList<>();
        List<TypeNode> weak = new ArrayList<>();
        seeds.forEach((t, score) -> (score >= 0.75 * top ? strong : weak).add(t));
        for (TypeNode t : strong) {
            if (slice.add(t)) {
                ordered.add(t);
            }
        }
        ReverseIndex rev = graph.reverse();
        for (TypeNode seed : strong) {
            for (TypeNode n : collaborators(graph, rev, integ, seed)) {
                if (ordered.size() < maxTypes && !n.file().isTest() && slice.add(n)) {
                    ordered.add(n);
                }
            }
        }
        for (TypeNode t : weak) {
            if (ordered.size() < maxTypes && slice.add(t)) {
                ordered.add(t);
            }
        }
        seeds.keySet().retainAll(slice);

        out.line("Slice for: " + task);
        Set<String> entries = new LinkedHashSet<>();
        for (TypeNode seed : seeds.keySet()) {
            for (ReverseIndex.EntryPoint e : rev.entryPoints(seed, null)) {
                entries.add(e.route() != null
                        ? "route " + e.route().httpMethod() + " " + e.route().path() + " -> " + e.route().owner().name() + "#" + e.route().handler()
                        : e.consumer().system() + " " + e.consumer().channel() + " -> " + e.consumer().owner().name() + "#" + e.consumer().method().name());
            }
        }
        if (!entries.isEmpty()) {
            out.line("Entry points: " + String.join(" · ", entries.stream().limit(6).toList())
                    + (entries.size() > 6 ? " · +" + (entries.size() - 6) + " more" : ""));
        }
        for (TypeNode t : ordered) {
            skeleton(graph, integ, t, seeds.containsKey(t), out);
        }

        // Bodies: the best-scoring methods; failing that, the matching seed's methods named like the task.
        List<MethodDecl> bodies = new ArrayList<>(methodScores.keySet());
        bodies.removeIf(m -> !slice.contains(methodOwner.get(m)) || m.endLine() <= m.line());
        bodies.sort((a, b) -> Double.compare(methodScores.get(b), methodScores.get(a)));
        double bestBody = bodies.isEmpty() ? 0 : methodScores.get(bodies.get(0));
        int shown = 0;
        for (MethodDecl m : bodies) {
            if (shown >= MAX_BODIES || methodScores.get(m) < 0.6 * bestBody) {
                break; // a body is worth its tokens only when it matches about as well as the best one
            }
            if (body(snapshot, methodOwner.get(m), m, out)) {
                shown++;
            }
        }
        if (shown == 0) {
            out.blank();
            out.line("No method matched the task words closely enough to include its source; the skeletons above give line numbers to open.");
        }
    }

    /** Who a seed works with: what it injects, its implementations or interfaces, its data, and its direct callers. */
    private static List<TypeNode> collaborators(CodeGraph graph, ReverseIndex rev, Integrations integ, TypeNode seed) {
        List<TypeNode> out = new ArrayList<>();
        for (InjectionPoint ip : seed.decl().injections()) {
            TypeNode t = graph.resolve(ip.type(), seed);
            if (t != null) {
                out.add(t);
                if (t.isInterface()) {
                    out.addAll(graph.implementations(t));
                }
                Integrations.Repo injectedRepo = integ.repoOf(t);
                if (injectedRepo != null && injectedRepo.entity() != null) {
                    out.add(injectedRepo.entity()); // the data the seed reads and writes
                }
            }
        }
        if (seed.isInterface()) {
            out.addAll(graph.implementations(seed));
        }
        out.addAll(rev.supertypes(seed));
        Integrations.Repo repo = integ.repoOf(seed);
        if (repo != null && repo.entity() != null) {
            out.add(repo.entity());
        }
        if (integ.entityOf(seed) != null) {
            integ.reposFor(seed).forEach(r -> out.add(r.repo()));
        }
        rev.callersOf(seed).stream().limit(2).forEach(r -> out.add(r.owner()));
        return out;
    }

    private static void skeleton(CodeGraph graph, Integrations integ, TypeNode t, boolean seed, Answer out) {
        var d = t.decl();
        String kind = graph.beanKind(t);
        out.blank();
        out.line("== " + t.name() + (kind == null ? "" : " [" + kind + "]") + (seed ? "" : " (collaborator)") + "  "
                + out.ref(t.file(), d.line()) + "-" + d.endLine());
        StringJoiner header = new StringJoiner(" ", "  ", "");
        d.annotations().forEach(a -> header.add(annotation(a, integ, t)));
        header.add(d.kind() + " " + d.name());
        if (!d.extendsTypes().isEmpty()) {
            header.add("extends " + String.join(", ", d.extendsTypes()));
        }
        if (!d.implementsTypes().isEmpty()) {
            header.add("implements " + String.join(", ", d.implementsTypes()));
        }
        out.line(header.toString());
        if (!d.injections().isEmpty()) {
            out.line("  inject: " + String.join(", ", d.injections().stream().map(i -> i.type() + " " + i.name()).toList()));
        }
        Integrations.Entity entity = integ.entityOf(t);
        if (entity != null) {
            List<String> fields = new ArrayList<>();
            for (FieldDecl f : d.fields()) {
                if (!f.isStatic()) {
                    fields.add(f.type() + " " + f.name());
                }
            }
            out.line("  " + (entity.store().equals("mongodb") ? "collection " : "table ") + entity.name() + ": " + String.join(", ", fields));
        } else if (d.kind().equals("record") || d.kind().equals("enum")) {
            // a record's components and an enum's constants are its whole shape; the header line carries the name
            out.line("  (" + d.kind() + ": see " + out.ref(t.file(), d.line()) + ")");
        }
        int listed = 0;
        for (MethodDecl m : d.methods()) {
            if (m.name().equals(d.name())) {
                continue; // constructors add nothing an injection line does not say
            }
            if (listed++ >= MAX_METHODS_PER_TYPE) {
                out.line("  +" + (d.methods().size() - MAX_METHODS_PER_TYPE) + " more methods");
                break;
            }
            StringJoiner line = new StringJoiner(" ", "  :" + m.line() + "  ", "");
            m.annotations().forEach(a -> line.add(annotation(a, integ, t)));
            line.add(m.signature());
            List<String> calls = new ArrayList<>();
            for (Call c : graph.callees(t, m)) {
                if (c.target() != t && graph.beanKind(c.target()) != null) {
                    calls.add(c.target().name() + "." + c.methodName());
                }
            }
            String msgs = "";
            for (Integrations.Message msg : integ.messagesIn(t, m)) {
                msgs += (msg.producer() ? "  => " : "  <= ") + msg.system() + " " + msg.channel();
            }
            out.line(line + (calls.isEmpty() ? "" : "  -> " + String.join(", ", calls.stream().distinct().limit(6).toList())) + msgs);
        }
    }

    /** {@code @Name} or {@code @Name(value)} when the annotation carries one short value worth reading. */
    static String annotation(Ann a, Integrations integ, TypeNode context) {
        String v = a.firstAttr("value", "path", "name", "topics", "destination", "queues", "collection");
        if (v != null) {
            v = integ.resolveValue(v, context);
        }
        if (v == null || v.length() > 60) {
            return "@" + a.name();
        }
        return "@" + a.name() + "(" + v + ")";
    }

    private static boolean body(WorkspaceService.Snapshot snapshot, TypeNode owner, MethodDecl m, Answer out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(snapshot.workspace().absolute(owner.file()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        int from = Math.max(1, m.line());
        int to = Math.min(lines.size(), m.endLine());
        if (from > to) {
            return false;
        }
        out.blank();
        out.line("-- " + owner.name() + "#" + m.name() + "  " + out.ref(owner.file(), from) + "-" + to);
        int last = Math.min(to, from + MAX_BODY_LINES - 1);
        for (int i = from; i <= last; i++) {
            out.line(String.format("%4d | %s", i, lines.get(i - 1)));
        }
        if (last < to) {
            out.line("     | ... " + (to - last) + " more lines (to " + to + ")");
        }
        return true;
    }
}
