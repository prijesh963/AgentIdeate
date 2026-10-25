package dev.agentideate.graph;

import dev.agentideate.graph.CodeGraph.Call;
import dev.agentideate.graph.CodeGraph.ResolvedEndpoint;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.CallSite;
import dev.agentideate.index.FieldDecl;
import dev.agentideate.index.InjectionPoint;
import dev.agentideate.index.MethodDecl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who depends on what: the reverse of the call and injection edges, and the routes and message
 * consumers a change would be reached from. Built once per graph, on first use.
 */
public final class ReverseIndex {

    /** A method that calls into the target, at a source line. */
    public record Ref(TypeNode owner, MethodDecl method, int line) {
    }

    public record Injector(TypeNode owner, InjectionPoint point) {
    }

    /** What a change can be reached from: a route, or a message consumer and its channel. */
    public record EntryPoint(ResolvedEndpoint route, Integrations.Message consumer) {
    }

    public static final int MAX_DEPTH = 8;

    private final CodeGraph graph;
    private final Map<TypeNode, Map<String, List<Ref>>> callers = new IdentityHashMap<>();
    private final Map<TypeNode, List<Injector>> injectors = new IdentityHashMap<>();
    private final Map<String, List<ResolvedEndpoint>> routesByHandler = new HashMap<>();

    ReverseIndex(CodeGraph graph) {
        this.graph = graph;
        for (TypeNode n : graph.types()) {
            for (MethodDecl m : n.decl().methods()) {
                for (Call c : graph.callees(n, m)) {
                    add(c.target(), c.methodName(), new Ref(n, m, c.line()));
                    if (c.via() != null) {
                        add(c.via(), c.methodName(), new Ref(n, m, c.line()));
                    }
                }
            }
            for (InjectionPoint ip : n.decl().injections()) {
                TypeNode t = graph.resolve(ip.type(), n);
                if (t != null) {
                    injectors.computeIfAbsent(t, k -> new ArrayList<>()).add(new Injector(n, ip));
                }
            }
        }
        for (ResolvedEndpoint e : graph.endpoints()) {
            routesByHandler.computeIfAbsent(key(e.owner(), e.handler()), k -> new ArrayList<>()).add(e);
        }
    }

    private void add(TypeNode target, String method, Ref ref) {
        List<Ref> list = callers.computeIfAbsent(target, k -> new LinkedHashMap<>())
                .computeIfAbsent(method, k -> new ArrayList<>());
        for (Ref r : list) {
            if (r.owner() == ref.owner() && r.method() == ref.method()) {
                return; // one entry per calling method is enough
            }
        }
        list.add(ref);
    }

    private static String key(TypeNode t, String method) {
        return System.identityHashCode(t) + "#" + method;
    }

    public List<Ref> callersOf(TypeNode t, String method) {
        return callers.getOrDefault(t, Map.of()).getOrDefault(method, List.of());
    }

    /** Callers of any method of {@code t}, excluding {@code t}'s own internal calls. */
    public List<Ref> callersOf(TypeNode t) {
        List<Ref> out = new ArrayList<>();
        for (List<Ref> refs : callers.getOrDefault(t, Map.of()).values()) {
            for (Ref r : refs) {
                if (r.owner() != t && out.stream().noneMatch(x -> x.owner() == r.owner() && x.method() == r.method())) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** Classes injecting {@code t} or one of the interfaces/superclasses it is injected as. */
    public List<Injector> injectorsOf(TypeNode t) {
        List<Injector> out = new ArrayList<>(injectors.getOrDefault(t, List.of()));
        for (TypeNode sup : supertypes(t)) {
            out.addAll(injectors.getOrDefault(sup, List.of()));
        }
        return out;
    }

    /** Declared supertypes found in the index, transitively. */
    public List<TypeNode> supertypes(TypeNode t) {
        List<TypeNode> out = new ArrayList<>();
        Set<TypeNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<TypeNode> queue = new ArrayDeque<>(List.of(t));
        while (!queue.isEmpty()) {
            TypeNode n = queue.poll();
            List<String> sups = new ArrayList<>(n.decl().extendsTypes());
            sups.addAll(n.decl().implementsTypes());
            for (String s : sups) {
                TypeNode p = graph.resolve(s, n);
                if (p != null && p != t && seen.add(p)) {
                    out.add(p);
                    queue.add(p);
                }
            }
        }
        return out;
    }

    /**
     * Routes and message consumers from which {@code method} of {@code t} (any method when
     * {@code null}) is reachable through calls, walking callers up to {@link #MAX_DEPTH} levels.
     */
    public List<EntryPoint> entryPoints(TypeNode t, String method) {
        Integrations integrations = graph.integrations();
        List<EntryPoint> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        if (method == null) {
            for (MethodDecl m : t.decl().methods()) {
                queue.add(new Object[]{t, m.name(), 0});
            }
            callers.getOrDefault(t, Map.of()).keySet().forEach(m -> queue.add(new Object[]{t, m, 0}));
        } else {
            queue.add(new Object[]{t, method, 0});
        }
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            TypeNode type = (TypeNode) item[0];
            String name = (String) item[1];
            int depth = (int) item[2];
            if (!seen.add(key(type, name))) {
                continue;
            }
            for (ResolvedEndpoint e : routesByHandler.getOrDefault(key(type, name), List.of())) {
                out.add(new EntryPoint(e, null));
            }
            MethodDecl decl = type.method(name);
            if (decl != null) {
                for (Integrations.Message msg : integrations.messagesIn(type, decl)) {
                    if (!msg.producer()) {
                        out.add(new EntryPoint(null, msg));
                    }
                }
            }
            if (depth >= MAX_DEPTH) {
                continue;
            }
            for (Ref r : callersOf(type, name)) {
                queue.add(new Object[]{r.owner(), r.method().name(), depth + 1});
            }
        }
        return out;
    }

    /**
     * Test classes that reference {@code t}: through a field, an injection, a call receiver, an
     * import, or by the {@code <Name>Test(s)}/{@code <Name>IT} naming convention.
     */
    public List<TypeNode> testsOf(TypeNode t) {
        List<TypeNode> out = new ArrayList<>();
        Set<String> conventional = Set.of(t.name() + "Test", t.name() + "Tests", t.name() + "IT");
        for (TypeNode test : graph.types()) {
            if (!test.file().isTest() || test == t) {
                continue;
            }
            if (conventional.contains(test.name()) || references(test, t)) {
                out.add(test);
            }
        }
        return out;
    }

    private boolean references(TypeNode from, TypeNode t) {
        for (FieldDecl f : from.decl().fields()) {
            if (graph.resolve(f.type(), from) == t) {
                return true;
            }
        }
        for (MethodDecl m : from.decl().methods()) {
            for (CallSite c : m.calls()) {
                if (c.scopeType() != null && c.scopeType().equals(t.name()) && graph.resolve(c.scopeType(), from) == t) {
                    return true;
                }
            }
        }
        for (String imp : from.file().imports()) {
            if (imp.equals(t.fqn())) {
                return true;
            }
        }
        return false;
    }
}
