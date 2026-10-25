package dev.agentideate.graph;

import dev.agentideate.index.Endpoint;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.index.SourceFile;
import dev.agentideate.index.TypeDecl;
import dev.agentideate.workspace.Workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-file and cross-repo view over the index: resolves a type name written in one file to the
 * declaration it means, and from there answers which beans exist, which routes are exposed, which
 * implementation an injected interface reaches, and what a method calls.
 *
 * <p>Immutable once built; rebuilt only when the index reports a change.
 */
public final class CodeGraph {

    /** Spring Data base interfaces: an interface extending one of these is a repository bean. */
    static final Set<String> SPRING_DATA_BASES = Set.of(
            "Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
            "ListPagingAndSortingRepository", "JpaRepository", "MongoRepository", "ReactiveMongoRepository",
            "ReactiveCrudRepository", "R2dbcRepository", "CassandraRepository", "ElasticsearchRepository",
            "JpaSpecificationExecutor", "QuerydslPredicateExecutor");

    private static final Pattern CONSTANT_REF = Pattern.compile("\\$\\{([A-Za-z_$][\\w$.]*)}");

    /** One declared type. Identity-compared: two nodes are equal only if they are the same declaration. */
    public static final class TypeNode {
        private final SourceFile file;
        private final TypeDecl decl;

        TypeNode(SourceFile file, TypeDecl decl) {
            this.file = file;
            this.decl = decl;
        }

        public SourceFile file() {
            return file;
        }

        public TypeDecl decl() {
            return decl;
        }

        public String name() {
            return decl.name();
        }

        public String fqn() {
            return decl.fqn();
        }

        public boolean isInterface() {
            return decl.kind().equals("interface");
        }

        public MethodDecl method(String name) {
            for (MethodDecl m : decl.methods()) {
                if (m.name().equals(name)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** An HTTP route with class and method paths joined and constants resolved. */
    public record ResolvedEndpoint(TypeNode owner, String httpMethod, String path, String handler, int line,
                                   boolean client) {
    }

    /**
     * A resolved call. {@code via} is the interface the call was written against when it was
     * dispatched to an implementation, otherwise {@code null}. {@code method} is {@code null} when
     * the target type is known but the method is inherited from outside the index (e.g. Spring
     * Data's {@code findById}).
     */
    public record Call(TypeNode target, MethodDecl method, String methodName, int line, TypeNode via) {
    }

    private final Workspace workspace;
    private final List<SourceFile> files;
    private final List<TypeNode> types = new ArrayList<>();
    private final Map<String, List<TypeNode>> byFqn = new HashMap<>();
    private final Map<String, List<TypeNode>> bySimple = new HashMap<>();
    private final Map<TypeNode, List<TypeNode>> directSubtypes = new IdentityHashMap<>();
    private final Map<TypeNode, String> beanKinds = new IdentityHashMap<>();

    public CodeGraph(Workspace workspace, Collection<SourceFile> files) {
        this.workspace = workspace;
        this.files = List.copyOf(files);
        for (SourceFile f : this.files) {
            for (TypeDecl t : f.types()) {
                TypeNode n = new TypeNode(f, t);
                types.add(n);
                byFqn.computeIfAbsent(t.fqn(), k -> new ArrayList<>()).add(n);
                bySimple.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(n);
            }
        }
        for (TypeNode n : types) {
            List<String> supers = new ArrayList<>(n.decl().extendsTypes());
            supers.addAll(n.decl().implementsTypes());
            for (String s : supers) {
                TypeNode parent = resolve(s, n);
                if (parent != null && parent != n) {
                    directSubtypes.computeIfAbsent(parent, k -> new ArrayList<>()).add(n);
                }
            }
        }
        for (TypeNode n : types) {
            String kind = computeBeanKind(n, new HashSet<>());
            if (kind != null) {
                beanKinds.put(n, kind);
            }
        }
    }

    public Workspace workspace() {
        return workspace;
    }

    public List<SourceFile> files() {
        return files;
    }

    public List<TypeNode> types() {
        return types;
    }

    public List<TypeNode> bySimpleName(String name) {
        return bySimple.getOrDefault(name, List.of());
    }

    /**
     * Resolves a type name as written in {@code context}'s file. Tries, in order: a fully qualified
     * name, a nested type, an explicit import, the same package, wildcard imports, and finally a
     * name that is unique in the context's module, root, or the whole workspace. An ambiguous name
     * resolves to {@code null} rather than to a guess.
     */
    public TypeNode resolve(String name, TypeNode context) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (name.contains(".")) {
            TypeNode q = pick(byFqn.get(name), context);
            if (q != null) {
                return q;
            }
            name = name.substring(name.lastIndexOf('.') + 1);
        }
        List<TypeNode> candidates = bySimple.get(name);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (context == null) {
            return candidates.size() == 1 ? candidates.get(0) : null;
        }
        String ctxFqn = context.fqn();
        TypeNode nested = pick(byFqn.get(ctxFqn + "." + name), context);
        if (nested != null) {
            return nested;
        }
        int lastDot = ctxFqn.lastIndexOf('.');
        if (lastDot > 0 && !context.file().packageName().equals(ctxFqn.substring(0, lastDot))) {
            // context is itself nested: a sibling nested type
            TypeNode sibling = pick(byFqn.get(ctxFqn.substring(0, lastDot) + "." + name), context);
            if (sibling != null) {
                return sibling;
            }
        }
        for (String imp : context.file().imports()) {
            if (!imp.startsWith("static ") && imp.endsWith("." + name)) {
                TypeNode imported = pick(byFqn.get(imp), context);
                if (imported != null) {
                    return imported;
                }
            }
        }
        String pkg = context.file().packageName();
        TypeNode samePkg = pick(byFqn.get(pkg.isEmpty() ? name : pkg + "." + name), context);
        if (samePkg != null) {
            return samePkg;
        }
        for (String imp : context.file().imports()) {
            if (!imp.startsWith("static ") && imp.endsWith(".*")) {
                TypeNode wild = pick(byFqn.get(imp.substring(0, imp.length() - 1) + name), context);
                if (wild != null) {
                    return wild;
                }
            }
        }
        List<TypeNode> sameModule = new ArrayList<>();
        List<TypeNode> sameRoot = new ArrayList<>();
        for (TypeNode c : candidates) {
            if (c.file().root().equals(context.file().root())) {
                sameRoot.add(c);
                if (c.file().module().equals(context.file().module())) {
                    sameModule.add(c);
                }
            }
        }
        if (sameModule.size() == 1) {
            return sameModule.get(0);
        }
        if (sameModule.isEmpty() && sameRoot.size() == 1) {
            return sameRoot.get(0);
        }
        if (sameRoot.isEmpty() && candidates.size() == 1) {
            return candidates.get(0);
        }
        return null;
    }

    /** Among same-FQN declarations (e.g. the same class name in two services), prefer the context's module, then root. */
    private static TypeNode pick(List<TypeNode> sameFqn, TypeNode context) {
        if (sameFqn == null || sameFqn.isEmpty()) {
            return null;
        }
        if (sameFqn.size() == 1 || context == null) {
            return sameFqn.get(0);
        }
        TypeNode rootMatch = null;
        for (TypeNode n : sameFqn) {
            if (n.file().root().equals(context.file().root())) {
                if (n.file().module().equals(context.file().module())) {
                    return n;
                }
                if (rootMatch == null) {
                    rootMatch = n;
                }
            }
        }
        return rootMatch != null ? rootMatch : sameFqn.get(0);
    }

    /** Concrete classes reachable below {@code type}: implementors, their subclasses, sub-interfaces' implementors. */
    public List<TypeNode> implementations(TypeNode type) {
        List<TypeNode> out = new ArrayList<>();
        Set<TypeNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<TypeNode> queue = new ArrayDeque<>(directSubtypes.getOrDefault(type, List.of()));
        while (!queue.isEmpty()) {
            TypeNode n = queue.poll();
            if (!seen.add(n)) {
                continue;
            }
            if (!n.isInterface() && !n.decl().kind().equals("annotation")) {
                out.add(n);
            }
            queue.addAll(directSubtypes.getOrDefault(n, List.of()));
        }
        return out;
    }

    /**
     * What Spring makes of this type: {@code application}, {@code controller}, {@code service},
     * {@code repository}, {@code component}, {@code configuration}, {@code spring-data-repository},
     * {@code feign-client}, or {@code null} for a type that is not a bean.
     */
    public String beanKind(TypeNode n) {
        return beanKinds.get(n);
    }

    private String computeBeanKind(TypeNode n, Set<String> guard) {
        TypeDecl d = n.decl();
        if (d.hasAnnotation("SpringBootApplication")) {
            return "application";
        }
        if (d.hasAnnotation("RestController") || d.hasAnnotation("Controller")) {
            return "controller";
        }
        if (d.hasAnnotation("FeignClient")) {
            return "feign-client";
        }
        if (d.hasAnnotation("Service")) {
            return "service";
        }
        if (n.isInterface() && isSpringData(n, guard)) {
            return "spring-data-repository";
        }
        if (d.hasAnnotation("Repository")) {
            return "repository";
        }
        if (d.hasAnnotation("Configuration")) {
            return "configuration";
        }
        if (d.hasAnnotation("Component")) {
            return "component";
        }
        return null;
    }

    private boolean isSpringData(TypeNode n, Set<String> guard) {
        if (!guard.add(n.fqn() + "@" + n.file().key())) {
            return false;
        }
        for (String s : n.decl().extendsTypes()) {
            TypeNode parent = resolve(s, n);
            if (parent == null) {
                if (SPRING_DATA_BASES.contains(s)) {
                    return true;
                }
            } else if (parent.isInterface() && isSpringData(parent, guard)) {
                return true;
            }
        }
        return false;
    }

    /** Routes this workspace exposes, sorted by path. Feign client mappings are excluded: they are calls out. */
    public List<ResolvedEndpoint> endpoints() {
        return collectEndpoints(false);
    }

    /** Outbound HTTP calls declared by Feign clients. */
    public List<ResolvedEndpoint> clientEndpoints() {
        return collectEndpoints(true);
    }

    private List<ResolvedEndpoint> collectEndpoints(boolean client) {
        List<ResolvedEndpoint> out = new ArrayList<>();
        for (TypeNode n : types) {
            for (Endpoint e : n.decl().endpoints()) {
                if (e.client() != client) {
                    continue;
                }
                String path = joinPaths(resolveConstants(n.decl().basePath(), n), resolveConstants(e.path(), n));
                out.add(new ResolvedEndpoint(n, e.httpMethod(), path, e.handler(), e.line(), e.client()));
            }
        }
        out.sort((a, b) -> {
            int c = a.path().compareTo(b.path());
            return c != 0 ? c : a.httpMethod().compareTo(b.httpMethod());
        });
        return out;
    }

    static String joinPaths(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        String joined = (b.isEmpty() ? "" : (b.startsWith("/") ? b : "/" + b))
                + (p.isEmpty() ? "" : (p.startsWith("/") ? p : "/" + p));
        joined = joined.replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined.isEmpty() ? "/" : joined;
    }

    /**
     * Replaces {@code ${NAME}} and {@code ${Owner.NAME}} with the constant's value. Looks in the
     * context type, then its static imports, then a constant name unique across the workspace. A
     * reference that cannot be resolved stays as {@code ${...}} so it is visibly unresolved rather
     * than mistaken for literal path text.
     */
    public String resolveConstants(String value, TypeNode context) {
        return resolveConstants(value, context, 0);
    }

    private String resolveConstants(String value, TypeNode context, int depth) {
        if (value == null || !value.contains("${") || depth > 5) {
            return value;
        }
        Matcher m = CONSTANT_REF.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            String resolved = lookupConstant(ref, context, depth);
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved != null ? resolved : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String lookupConstant(String ref, TypeNode context, int depth) {
        int dot = ref.lastIndexOf('.');
        if (dot > 0) {
            String owner = ref.substring(0, dot);
            String name = ref.substring(dot + 1);
            TypeNode ownerNode = resolve(owner, context);
            if (ownerNode != null && ownerNode.decl().constants().containsKey(name)) {
                return resolveConstants(ownerNode.decl().constants().get(name), ownerNode, depth + 1);
            }
            return null;
        }
        if (context != null && context.decl().constants().containsKey(ref)) {
            return resolveConstants(context.decl().constants().get(ref), context, depth + 1);
        }
        if (context != null) {
            for (String imp : context.file().imports()) {
                if (!imp.startsWith("static ")) {
                    continue;
                }
                String target = imp.substring("static ".length());
                String ownerFqn;
                if (target.endsWith("." + ref)) {
                    ownerFqn = target.substring(0, target.length() - ref.length() - 1);
                } else if (target.endsWith(".*")) {
                    ownerFqn = target.substring(0, target.length() - 2);
                } else {
                    continue;
                }
                TypeNode owner = pick(byFqn.get(ownerFqn), context);
                if (owner != null && owner.decl().constants().containsKey(ref)) {
                    return resolveConstants(owner.decl().constants().get(ref), owner, depth + 1);
                }
            }
        }
        TypeNode unique = null;
        for (TypeNode n : types) {
            if (n.decl().constants().containsKey(ref)) {
                if (unique != null) {
                    return null; // ambiguous: two constants share this name
                }
                unique = n;
            }
        }
        return unique == null ? null : resolveConstants(unique.decl().constants().get(ref), unique, depth + 1);
    }

    /**
     * Calls made by {@code method} of {@code owner} into types in the index. A call on an interface
     * is dispatched to each concrete implementation found; with none (a Spring Data repository, a
     * Feign client) it stays on the interface. Calls into types outside the index are not returned.
     */
    public List<Call> callees(TypeNode owner, MethodDecl method) {
        Map<String, Call> out = new LinkedHashMap<>();
        for (var site : method.calls()) {
            if (site.scopeType() == null) {
                MethodDecl own = owner.method(site.method());
                if (own != null && own != method) {
                    out.putIfAbsent(owner.fqn() + "#" + site.method(), new Call(owner, own, site.method(), site.line(), null));
                }
                continue;
            }
            TypeNode target = resolve(site.scopeType(), owner);
            if (target == null) {
                continue;
            }
            List<TypeNode> impls = target.isInterface() ? implementations(target) : List.of();
            if (impls.isEmpty()) {
                out.putIfAbsent(target.fqn() + "#" + site.method(),
                        new Call(target, target.method(site.method()), site.method(), site.line(), null));
            } else {
                for (TypeNode impl : impls) {
                    out.putIfAbsent(impl.fqn() + "#" + site.method(),
                            new Call(impl, impl.method(site.method()), site.method(), site.line(), target));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Build modules that contain a {@code @SpringBootApplication}: the deployable services. */
    public Map<String, TypeNode> services() {
        Map<String, TypeNode> out = new LinkedHashMap<>();
        for (TypeNode n : types) {
            if ("application".equals(beanKinds.get(n))) {
                out.putIfAbsent(moduleKey(n.file()), n);
            }
        }
        return out;
    }

    /**
     * A module's display name: {@code root/module} when there are several roots, {@code module}
     * otherwise. A root-level module is named after its root, or {@code "."} in a single-root workspace.
     */
    public String moduleKey(SourceFile f) {
        if (workspace.multiRoot()) {
            return f.module().isEmpty() ? f.root() : f.root() + "/" + f.module();
        }
        return f.module().isEmpty() ? "." : f.module();
    }
}
