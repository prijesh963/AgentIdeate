package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.ResolvedEndpoint;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds classes, methods, routes and config files by name. Matches on words, so "invoice approval"
 * finds {@code InvoiceApprovalService}. When nothing matches it says what was searched, so an
 * absence is reported as an absence and not left to be guessed at.
 */
final class LocateTool implements Tool {

    static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "where", "what", "which", "how", "does", "do", "of", "for", "in",
            "to", "and", "or", "it", "this", "that", "file", "files", "class", "method", "implemented",
            "implementation", "find", "show", "me", "located", "lives", "defined", "code", "already");

    private static final List<String> KINDS = List.of("class", "route", "method", "file");

    record Candidate(String kind, String label, SourceFile file, int line, List<String> primary,
                     List<String> secondary, String exactName) {
    }

    record Hit(Candidate c, double score) {
    }

    @Override
    public String name() {
        return "locate";
    }

    @Override
    public String description() {
        return "Find where something lives: classes, methods, REST routes, config files, by name or words "
                + "(e.g. 'invoice approval'). Returns file:line. Says explicitly when nothing exists.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("query").put("type", "string").put("description", "Name, words, or a route path");
        ObjectNode kind = p.putObject("kind");
        kind.put("type", "string").put("description", "Restrict to one kind");
        var kinds = kind.putArray("enum");
        KINDS.forEach(kinds::add);
        p.putObject("limit").put("type", "integer").put("description", "Max results (default 10)");
        s.putArray("required").add("query");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        String query = args.required("query");
        String kind = args.string("kind");
        if (kind != null && !KINDS.contains(kind)) {
            throw new ToolException("Unknown kind '" + kind + "'. Use one of: " + String.join(", ", KINDS));
        }
        int limit = args.integer("limit", 10, 1, 50);
        List<String> q = queryTokens(query);
        if (q.isEmpty()) {
            throw new ToolException("The query has no searchable words: '" + query + "'.");
        }
        String compact = String.join("", q);

        List<Candidate> candidates = candidates(graph);
        List<Hit> hits = new ArrayList<>();
        int needed = Math.max(1, (int) Math.ceil(q.size() * 0.5));
        for (Candidate c : candidates) {
            if (kind != null && !kind.equals(c.kind())) {
                continue;
            }
            double score = 0;
            int matched = 0;
            for (String t : q) {
                double s = Math.max(match(t, c.primary()), match(t, c.secondary()) * 0.4);
                if (s > 0) {
                    matched++;
                    score += s;
                }
            }
            if (matched < needed) {
                continue;
            }
            if (c.exactName() != null && c.exactName().equals(compact)) {
                score += 6;
            }
            score += 0.5 * matched / q.size();
            hits.add(new Hit(c, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparingInt(h -> KINDS.indexOf(h.c().kind()))
                .thenComparing(h -> h.c().label()));

        if (hits.isEmpty()) {
            long types = candidates.stream().filter(c -> c.kind().equals("class")).count();
            long methods = candidates.stream().filter(c -> c.kind().equals("method")).count();
            long routes = candidates.stream().filter(c -> c.kind().equals("route")).count();
            long configs = candidates.stream().filter(c -> c.kind().equals("file")).count();
            out.line("Not found: nothing named like \"" + query + "\" among " + types + " classes, " + methods
                    + " methods, " + routes + " routes and " + configs + " config files.");
            out.line("Searched names of Java types, methods, route paths and config file paths. Not searched: "
                    + "comments, string contents, non-Java file contents.");
            return;
        }
        out.line("locate \"" + query + "\": " + hits.size() + " match" + (hits.size() == 1 ? "" : "es")
                + (hits.size() > limit ? ", top " + limit : ""));
        for (Hit h : hits.subList(0, Math.min(limit, hits.size()))) {
            Candidate c = h.c();
            String where = c.line() > 0 ? out.ref(c.file(), c.line()) : out.ref(c.file());
            out.line(String.format("  %-6s %s  %s", c.kind(), c.label(), where));
        }
    }

    private static List<Candidate> candidates(CodeGraph graph) {
        List<Candidate> out = new ArrayList<>();
        for (TypeNode n : graph.types()) {
            String bean = graph.beanKind(n);
            String label = n.name() + (bean != null ? " [" + bean + "]" : "") + (n.decl().kind().equals("class") ? "" : " (" + n.decl().kind() + ")");
            // Secondary words: the type's own package segment ("web", "billing"), not the whole path,
            // which would make every class in a module match the module's name.
            String pkg = n.file().packageName();
            out.add(new Candidate("class", label, n.file(), n.decl().line(), words(n.name()),
                    words(pkg.substring(pkg.lastIndexOf('.') + 1)), n.name().toLowerCase(Locale.ROOT)));
            for (MethodDecl m : n.decl().methods()) {
                if (m.name().equals(n.name())) {
                    continue; // constructors are found through their class
                }
                String sig = m.signature();
                int at = sig.indexOf(m.name() + "(");
                out.add(new Candidate("method", n.name() + "#" + (at >= 0 ? sig.substring(at) : sig), n.file(), m.line(), words(m.name()),
                        words(n.name()), m.name().toLowerCase(Locale.ROOT)));
            }
        }
        for (ResolvedEndpoint e : graph.endpoints()) {
            out.add(new Candidate("route", e.httpMethod() + " " + e.path() + " -> " + e.owner().name() + "#" + e.handler(),
                    e.owner().file(), e.line(), words(e.path()), words(e.handler()), null));
        }
        for (SourceFile f : graph.files()) {
            if (!f.isJava()) {
                String name = f.path().substring(f.path().lastIndexOf('/') + 1);
                out.add(new Candidate("file", name, f, 0, words(name), words(f.path()), name.toLowerCase(Locale.ROOT)));
            }
        }
        return out;
    }

    /** 3 for an exact word, 2 for a prefix either way (from 3 letters), 1 for a substring. */
    static double match(String token, List<String> words) {
        double best = 0;
        for (String w : words) {
            if (w.equals(token)) {
                return 3;
            }
            if (token.length() >= 3 && w.length() >= 3 && (w.startsWith(token) || token.startsWith(w))) {
                best = Math.max(best, 2);
            } else if (token.length() >= 4 && w.contains(token)) {
                best = Math.max(best, 1);
            }
        }
        return best;
    }

    static List<String> queryTokens(String query) {
        List<String> out = new ArrayList<>();
        for (String w : words(query)) {
            if (!STOPWORDS.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Splits camelCase, snake_case, paths and punctuation into lowercase words. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        String spaced = s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        for (String w : spaced.split("[^A-Za-z0-9]+")) {
            if (!w.isEmpty()) {
                out.add(w.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
