package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.graph.Integrations;
import dev.agentideate.graph.Integrations.Entity;
import dev.agentideate.graph.Integrations.Message;
import dev.agentideate.graph.Integrations.Repo;
import dev.agentideate.graph.ReverseIndex;
import dev.agentideate.graph.ReverseIndex.EntryPoint;
import dev.agentideate.graph.ReverseIndex.Injector;
import dev.agentideate.graph.ReverseIndex.Ref;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.WorkspaceService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a change would touch: for a class or method, its callers, injectors, the routes and message
 * consumers it is reached from, the data it maps to and the tests that cover it; for a topic, its
 * producers, consumers and the routes that lead to the producers; for a table, its entity. Across
 * every service and repo in the workspace. The basis for planning a change without exploring.
 */
final class ImpactTool implements Tool {

    static final int MAX_PER_SECTION = 15;

    @Override
    public String name() {
        return "impact_of";
    }

    @Override
    public String description() {
        return "What a change touches: callers, injectors, routes and Kafka consumers it is reached from, "
                + "tables, tests, across services. Target: Class, Class#method, topic, or table.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ((ObjectNode) s.get("properties")).putObject("target").put("type", "string")
                .put("description", "OrderService, OrderService#placeOrder, a.b.OrderService, order.created, or orders");
        s.putArray("required").add("target");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        String target = args.required("target");
        Integrations integ = graph.integrations();

        String typePart = target;
        String methodPart = null;
        int hash = target.indexOf('#');
        if (hash > 0) {
            typePart = target.substring(0, hash);
            methodPart = target.substring(hash + 1).replaceAll("\\(.*", "");
        }
        List<TypeNode> types = findTypes(graph, typePart);
        if (types.isEmpty() && methodPart == null && target.contains(".")) {
            // "OrderService.placeOrder": a dot before a lower-case last segment is a method
            int dot = target.lastIndexOf('.');
            String last = target.substring(dot + 1);
            if (!last.isEmpty() && Character.isLowerCase(last.charAt(0))) {
                List<TypeNode> owners = findTypes(graph, target.substring(0, dot));
                if (!owners.isEmpty()) {
                    types = owners;
                    methodPart = last;
                }
            }
        }
        if (types.size() > 1) {
            out.line("\"" + typePart + "\" is ambiguous: " + types.size() + " types have that name. Ask again with the qualified name:");
            types.stream().limit(MAX_PER_SECTION).forEach(t -> out.line("  " + t.fqn() + "  " + out.ref(t.file(), t.decl().line())));
            return;
        }
        if (types.size() == 1) {
            TypeNode t = types.get(0);
            if (methodPart != null && t.method(methodPart) == null && graph.reverse().callersOf(t, methodPart).isEmpty()) {
                throw new ToolException("Not found: " + t.name() + " has no method '" + methodPart + "'. Its methods: "
                        + String.join(", ", t.decl().methods().stream().map(MethodDecl::name).distinct().toList()));
            }
            typeImpact(graph, t, methodPart, out);
            return;
        }

        List<Message> channel = integ.messages().stream().filter(m -> m.resolved() && m.channel().equals(target)).toList();
        if (!channel.isEmpty()) {
            channelImpact(graph, target, channel, out);
            return;
        }
        for (Entity e : integ.entities()) {
            if (e.name().equalsIgnoreCase(target)) {
                out.line(target + " is the " + (e.store().equals("mongodb") ? "collection" : "table") + " of " + e.type().name() + ".");
                typeImpact(graph, e.type(), null, out);
                return;
            }
        }
        for (SourceFile f : graph.files()) {
            if (f.tables().contains(target.toLowerCase(Locale.ROOT))) {
                out.line(target + " appears only in migrations, with no entity mapped to it:");
                graph.files().stream().filter(x -> x.tables().contains(target.toLowerCase(Locale.ROOT)))
                        .forEach(x -> out.line("  " + out.ref(x)));
                out.line("Code may still reach it through SQL strings (JdbcTemplate), which are not indexed.");
                return;
            }
        }
        out.line("Not found: no class, method, topic/queue or table named \"" + target + "\".");
        out.line("Use locate to find the right name; impact_of needs an exact class name (or Class#method), "
                + "a channel name as in messaging_map, or a table/collection name as in data_map.");
    }

    private static List<TypeNode> findTypes(CodeGraph graph, String name) {
        List<TypeNode> exact = new ArrayList<>();
        for (TypeNode t : graph.types()) {
            if (t.fqn().equals(name)) {
                exact.add(t);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        List<TypeNode> bySimple = graph.bySimpleName(name);
        List<TypeNode> main = bySimple.stream().filter(t -> !t.file().isTest()).toList();
        return main.isEmpty() ? bySimple : main;
    }

    private void typeImpact(CodeGraph graph, TypeNode t, String method, Answer out) {
        ReverseIndex rev = graph.reverse();
        Integrations integ = graph.integrations();
        String home = graph.moduleKey(t.file());
        MethodDecl m = method == null ? null : t.method(method);
        String kind = graph.beanKind(t);
        out.line("Impact of " + t.name() + (method != null ? "#" + method : "") + "  (" + home + ")  "
                + out.ref(t.file(), m != null ? m.line() : t.decl().line()));
        List<TypeNode> supers = rev.supertypes(t);
        List<TypeNode> impls = t.isInterface() ? graph.implementations(t) : List.of();
        Entity asEntity = integ.entityOf(t);
        StringBuilder what = new StringBuilder("  " + (kind != null ? kind
                : asEntity != null ? asEntity.store() + " entity" : t.decl().kind()));
        if (!supers.isEmpty()) {
            what.append(" · implements/extends ").append(String.join(", ", supers.stream().map(TypeNode::name).toList()));
        }
        if (!impls.isEmpty()) {
            what.append(" · implemented by ").append(String.join(", ", impls.stream().map(TypeNode::name).toList()));
        }
        out.line(what.toString());

        // callers: of this method, or of any method of the type; for an interface also via its implementations
        List<Ref> callers = new ArrayList<>(method != null ? rev.callersOf(t, method) : rev.callersOf(t));
        for (TypeNode sup : supers) {
            for (Ref r : method != null ? rev.callersOf(sup, method) : rev.callersOf(sup)) {
                if (callers.stream().noneMatch(x -> x.owner() == r.owner() && x.method() == r.method())) {
                    callers.add(r);
                }
            }
        }
        callers.removeIf(r -> r.owner() == t);
        section(out, "Called by", callers.size());
        callers.stream().limit(MAX_PER_SECTION).forEach(r -> out.line("  " + r.owner().name() + "#" + r.method().name()
                + elsewhere(graph, r.owner(), home) + "  " + out.ref(r.owner().file(), r.line())));
        more(out, callers.size());

        if (method == null) {
            List<Injector> injectors = rev.injectorsOf(t);
            if (!injectors.isEmpty()) {
                section(out, "Injected into", injectors.size());
                injectors.stream().limit(MAX_PER_SECTION).forEach(i -> out.line("  " + i.owner().name() + " ("
                        + (i.point().type().equals(t.name()) ? "" : "as " + i.point().type() + ", ") + i.point().via() + ")"
                        + elsewhere(graph, i.owner(), home) + "  " + out.ref(i.owner().file(), i.point().line())));
                more(out, injectors.size());
            }
        }

        List<EntryPoint> entries = rev.entryPoints(t, method);
        for (TypeNode sup : supers) {
            entries.addAll(rev.entryPoints(sup, method));
        }
        Set<String> entryLines = new LinkedHashSet<>();
        for (EntryPoint e : entries) {
            if (e.route() != null) {
                entryLines.add("  route " + e.route().httpMethod() + " " + e.route().path() + "  ("
                        + graph.moduleKey(e.route().owner().file()) + ")");
            } else {
                Message c = e.consumer();
                entryLines.add("  " + c.system() + " " + c.channel() + " consumed by " + c.owner().name() + "#" + c.method().name()
                        + "  (" + graph.moduleKey(c.owner().file()) + ")");
            }
        }
        section(out, "Reached from", entryLines.size());
        entryLines.stream().limit(MAX_PER_SECTION).forEach(out::line);
        more(out, entryLines.size());

        // messages this code sends, directly or through the beans it calls, and who receives them
        List<Message> sent = downstreamMessages(graph, t, method);
        if (!sent.isEmpty()) {
            section(out, "Sends (directly or via calls)", sent.size());
            for (Message s : sent) {
                List<Message> receivers = integ.counterparts(s);
                String to = receivers.isEmpty() ? "no consumer in this workspace"
                        : String.join(", ", receivers.stream().map(r -> graph.moduleKey(r.owner().file()) + " "
                        + r.owner().name() + "#" + r.method().name()).toList());
                out.line("  " + s.system() + " " + s.channel() + " -> " + to + "  " + out.ref(s.owner().file(), s.line()));
            }
        }

        Entity entity = integ.entityOf(t);
        Repo repo = integ.repoOf(t);
        if (entity == null && repo != null && repo.entity() != null) {
            entity = integ.entityOf(repo.entity());
        }
        if (entity != null) {
            String unit = entity.store().equals("mongodb") ? "collection" : "table";
            out.line("Data: " + unit + " " + entity.name() + " (" + entity.store() + ") <- " + entity.type().name()
                    + "  " + out.ref(entity.type().file(), entity.type().decl().line()));
            for (Repo r : integ.reposFor(entity.type())) {
                if (r.repo() != t) {
                    out.line("  repo " + r.repo().name() + "  " + out.ref(r.repo().file(), r.repo().decl().line()));
                }
            }
            for (SourceFile f : integ.migrationsFor(entity.name(), entity.type())) {
                out.line("  migration " + out.ref(f));
            }
        }

        List<TypeNode> tests = new ArrayList<>(rev.testsOf(t));
        for (TypeNode impl : impls) {
            rev.testsOf(impl).stream().filter(x -> !tests.contains(x)).forEach(tests::add);
        }
        for (TypeNode sup : supers) {
            rev.testsOf(sup).stream().filter(x -> !tests.contains(x)).forEach(tests::add);
        }
        section(out, "Tests", tests.size());
        tests.stream().limit(MAX_PER_SECTION).forEach(x -> out.line("  " + x.name() + "  " + out.ref(x.file(), x.decl().line())));
        if (tests.isEmpty()) {
            out.line("  none reference it");
        }
    }

    /** Producer messages in {@code method} (or any method of {@code t}) and in the in-index methods they call. */
    private static List<Message> downstreamMessages(CodeGraph graph, TypeNode t, String method) {
        List<Message> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        for (MethodDecl md : t.decl().methods()) {
            if (method == null || md.name().equals(method)) {
                queue.add(new Object[]{t, md, 0});
            }
        }
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            TypeNode type = (TypeNode) item[0];
            MethodDecl md = (MethodDecl) item[1];
            int depth = (int) item[2];
            if (!seen.add(System.identityHashCode(type) + "#" + md.name())) {
                continue;
            }
            for (Message msg : graph.integrations().messagesIn(type, md)) {
                if (msg.producer() && !out.contains(msg)) {
                    out.add(msg);
                }
            }
            if (depth < ReverseIndex.MAX_DEPTH) {
                for (CodeGraph.Call c : graph.callees(type, md)) {
                    if (c.method() != null) {
                        queue.add(new Object[]{c.target(), c.method(), depth + 1});
                    }
                }
            }
        }
        return out;
    }

    private void channelImpact(CodeGraph graph, String channel, List<Message> msgs, Answer out) {
        out.line("Impact of " + msgs.get(0).system() + " " + channel);
        List<Message> producers = msgs.stream().filter(Message::producer).toList();
        List<Message> consumers = new ArrayList<>(msgs.stream().filter(m -> !m.producer()).toList());
        if (!producers.isEmpty()) {
            graph.integrations().counterparts(producers.get(0)).stream()
                    .filter(c -> !consumers.contains(c)).forEach(consumers::add);
        }
        section(out, "Producers", producers.size());
        producers.forEach(p -> out.line("  " + MessagingMapTool.describe(graph, p, out)));
        section(out, "Consumers", consumers.size());
        consumers.forEach(c -> out.line("  " + MessagingMapTool.describe(graph, c, out)));
        if (consumers.isEmpty()) {
            out.line("  none in this workspace: changing the payload may break an external consumer");
        }
        Set<String> routes = new LinkedHashSet<>();
        for (Message p : producers) {
            for (EntryPoint e : graph.reverse().entryPoints(p.owner(), p.method().name())) {
                if (e.route() != null) {
                    routes.add("  route " + e.route().httpMethod() + " " + e.route().path() + "  ("
                            + graph.moduleKey(e.route().owner().file()) + ")");
                }
            }
        }
        section(out, "Produced when", routes.size());
        routes.forEach(out::line);
        out.line("A payload change must stay compatible with every consumer above, or change them in the same plan.");
    }

    private static String elsewhere(CodeGraph graph, TypeNode n, String home) {
        String module = graph.moduleKey(n.file());
        return module.equals(home) ? "" : "  [" + module + "]";
    }

    private static void section(Answer out, String title, int count) {
        out.line(title + " (" + count + "):");
    }

    private static void more(Answer out, int count) {
        if (count > MAX_PER_SECTION) {
            out.line("  +" + (count - MAX_PER_SECTION) + " more");
        }
    }
}
