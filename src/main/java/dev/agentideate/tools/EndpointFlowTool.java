package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.Call;
import dev.agentideate.graph.CodeGraph.ResolvedEndpoint;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Traces a REST route from its controller method through the beans it calls, following an injected
 * interface to its implementation and a Feign client to the route it reaches in another service.
 */
final class EndpointFlowTool implements Tool {

    static final int MAX_FLOWS = 3;
    static final int MAX_NODES = 60;

    @Override
    public String name() {
        return "endpoint_flow";
    }

    @Override
    public String description() {
        return "Trace a REST route through controller -> services -> repositories/clients, across services "
                + "via Feign. Returns the call tree with file:line, so the flow is known without opening files.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("path").put("type", "string").put("description", "Route path, e.g. /api/orders/{id} or /api/orders/42");
        p.putObject("method").put("type", "string").put("description", "HTTP method, e.g. GET (optional)");
        p.putObject("depth").put("type", "integer").put("description", "Call depth (default 4, max 8)");
        s.putArray("required").add("path");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        String path = normalize(args.required("path"));
        String method = args.string("method");
        method = method == null ? null : method.toUpperCase(Locale.ROOT);
        int depth = args.integer("depth", 4, 1, 8);

        List<ResolvedEndpoint> all = graph.endpoints();
        List<ResolvedEndpoint> matches = match(all, path, method, false);
        if (matches.isEmpty()) {
            matches = match(all, path, method, true);
        }
        if (matches.isEmpty()) {
            out.line("Not found: no route matches " + (method == null ? "" : method + " ") + path + " among "
                    + all.size() + " routes. Feign client mappings are calls out, not routes, and are not matched here.");
            String last = lastSegment(path);
            if (!last.isEmpty() && !last.startsWith("{")) {
                all.stream().filter(e -> e.path().contains(last)).limit(5)
                        .forEach(e -> out.line("  similar: " + e.httpMethod() + " " + e.path()));
            }
            return;
        }
        int shown = Math.min(MAX_FLOWS, matches.size());
        if (matches.size() > shown) {
            out.line(matches.size() + " routes match; showing " + shown + ". Pass 'method' or a fuller path to narrow.");
        }
        int[] budget = {MAX_NODES};
        for (ResolvedEndpoint e : matches.subList(0, shown)) {
            out.line(e.httpMethod() + " " + e.path() + "  (" + graph.moduleKey(e.owner().file()) + ")");
            MethodDecl handler = e.owner().method(e.handler());
            if (handler == null) {
                out.line("  handler " + e.owner().name() + "#" + e.handler() + "  " + out.ref(e.owner().file(), e.line()));
                continue;
            }
            Set<String> seen = new HashSet<>();
            node(graph, e.owner(), handler, null, "  ", depth, seen, budget, out);
            out.blank();
        }
        out.line("Not shown: calls into libraries/JDK, chained calls (a.b().c()), and Kafka hops (not indexed yet).");
    }

    private void node(CodeGraph graph, TypeNode type, MethodDecl m, TypeNode via, String indent, int depth,
                      Set<String> seen, int[] budget, Answer out) {
        if (budget[0]-- <= 0) {
            return;
        }
        String key = type.fqn() + "#" + m.name();
        String label = (via != null ? via.name() + "." + m.name() + " -> " : "") + type.name() + "#" + m.name();
        String kind = graph.beanKind(type);
        String tag = kind == null || kind.equals("service") || kind.equals("controller") || kind.equals("component")
                ? "" : " [" + kind + "]";
        if (!seen.add(key)) {
            out.line(indent + label + tag + "  (see above)");
            return;
        }
        out.line(indent + label + tag + "  " + out.ref(type.file(), m.line()));
        if (depth <= 0) {
            return;
        }
        TreeSet<String> dataTypes = new TreeSet<>();
        for (Call c : graph.callees(type, m)) {
            String childIndent = indent + "  ";
            if (c.target() != type && isDataAccess(graph, c)) {
                dataTypes.add(c.target().name());
                continue;
            }
            if (c.method() == null) {
                String ckind = graph.beanKind(c.target());
                String inherited = "spring-data-repository".equals(ckind) ? "inherited from Spring Data" : "inherited";
                out.line(childIndent + c.target().name() + "#" + c.methodName() + " [" + (ckind == null ? "" : ckind + ", ")
                        + inherited + "]  " + out.ref(c.target().file(), c.target().decl().line()));
                continue;
            }
            if ("feign-client".equals(graph.beanKind(c.target()))) {
                feignHop(graph, c, childIndent, depth - 1, seen, budget, out);
                continue;
            }
            node(graph, c.target(), c.method(), c.via(), childIndent, depth - 1, seen, budget, out);
        }
        if (!dataTypes.isEmpty()) {
            out.line(indent + "  uses data: " + String.join(", ", dataTypes));
        }
    }

    /** Annotations that mark a type as data (an entity, a document, a Lombok value) rather than logic. */
    static final Set<String> DATA_ANNOTATIONS = Set.of(
            "Entity", "Document", "Embeddable", "Table", "Data", "Value", "Getter", "Setter");

    /**
     * A call on a data type (record, enum, entity, DTO) or an accessor on a non-bean: summarized as
     * one "uses data" line instead of a node each, since a getter call explains nothing about the flow.
     */
    static boolean isDataAccess(CodeGraph graph, Call c) {
        TypeNode t = c.target();
        if (graph.beanKind(t) != null) {
            return false;
        }
        String kind = t.decl().kind();
        if (kind.equals("record") || kind.equals("enum") || c.method() == null) {
            return true;
        }
        if (t.decl().annotations().stream().anyMatch(a -> DATA_ANNOTATIONS.contains(a.name()))) {
            return true;
        }
        return c.methodName().matches("(get|set|is)[A-Z].*");
    }

    /** A Feign call: show the client method, then continue into the route it reaches if another indexed service exposes it. */
    private void feignHop(CodeGraph graph, Call c, String indent, int depth, Set<String> seen, int[] budget, Answer out) {
        ResolvedEndpoint client = null;
        for (ResolvedEndpoint ce : graph.clientEndpoints()) {
            if (ce.owner() == c.target() && ce.handler().equals(c.methodName())) {
                client = ce;
                break;
            }
        }
        String target = RepoOverviewTool.feignTarget(c.target());
        String call = client == null ? "" : " " + client.httpMethod() + " " + client.path();
        out.line(indent + c.target().name() + "#" + c.methodName() + " [feign -> " + target + call + "]  "
                + out.ref(c.target().file(), c.method().line()));
        if (client == null || depth <= 0) {
            return;
        }
        for (ResolvedEndpoint route : graph.endpoints()) {
            boolean methodOk = route.httpMethod().equals(client.httpMethod()) || route.httpMethod().equals("ANY");
            if (methodOk && templatesMatch(route.path(), client.path())
                    && !graph.moduleKey(route.owner().file()).equals(graph.moduleKey(c.target().file()))) {
                MethodDecl handler = route.owner().method(route.handler());
                if (handler != null) {
                    out.line(indent + "  => handled in " + graph.moduleKey(route.owner().file()) + ":");
                    node(graph, route.owner(), handler, null, indent + "    ", depth, seen, budget, out);
                }
            }
        }
    }

    static List<ResolvedEndpoint> match(List<ResolvedEndpoint> all, String path, String method, boolean loose) {
        List<ResolvedEndpoint> out = new ArrayList<>();
        for (ResolvedEndpoint e : all) {
            if (method != null && !e.httpMethod().equals(method) && !e.httpMethod().equals("ANY")) {
                continue;
            }
            boolean ok = loose ? e.path().endsWith(path) || e.path().contains(path) : templatesMatch(e.path(), path);
            if (ok) {
                out.add(e);
            }
        }
        return out;
    }

    /** Segment-wise equality where a {@code {var}} segment on either side matches anything. */
    static boolean templatesMatch(String a, String b) {
        String[] x = normalize(a).split("/");
        String[] y = normalize(b).split("/");
        if (x.length != y.length) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            boolean var = x[i].startsWith("{") || y[i].startsWith("{");
            if (!var && !x[i].equalsIgnoreCase(y[i])) {
                return false;
            }
        }
        return true;
    }

    static String normalize(String p) {
        String s = p.trim();
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String lastSegment(String path) {
        String[] parts = path.split("/");
        return parts.length == 0 ? path : parts[parts.length - 1];
    }
}
