package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.ResolvedEndpoint;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.graph.Integrations;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A map of the workspace: services, their routes grouped by controller, their beans, outbound
 * Feign calls and config files. What an agent would otherwise assemble by opening files one by one.
 */
final class RepoOverviewTool implements Tool {

    static final int MAX_ROUTES_PER_SERVICE = 30;

    private static final List<String> BEAN_ORDER = List.of(
            "controller", "service", "component", "repository", "spring-data-repository", "feign-client", "configuration");

    @Override
    public String name() {
        return "repo_overview";
    }

    @Override
    public String description() {
        return "Map of the Java/Spring workspace: services, REST routes by controller, beans, datastores, "
                + "Kafka topics, Feign calls, config. Call first, before opening files to learn the structure.";
    }

    @Override
    public boolean threaded() {
        return false; // a workspace-wide map, not part of any one topic
    }

    @Override
    public ObjectNode inputSchema() {
        return ToolRegistry.objectSchema();
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) {
        Map<String, List<SourceFile>> filesByModule = new TreeMap<>();
        for (SourceFile f : graph.files()) {
            filesByModule.computeIfAbsent(graph.moduleKey(f), k -> new ArrayList<>()).add(f);
        }
        Map<String, TypeNode> services = new TreeMap<>(graph.services());
        long javaFiles = graph.files().stream().filter(SourceFile::isJava).count();
        var ws = snapshot.workspace();
        out.line("Workspace " + ws.roots().get(0).name() + (ws.multiRoot() ? " +" + (ws.roots().size() - 1) + " more roots" : "")
                + " · " + filesByModule.size() + " modules · " + services.size() + " Spring Boot services · "
                + javaFiles + " Java files");
        if (ws.multiRoot()) {
            ws.roots().forEach(r -> out.line("  root " + r.name() + ": " + r.path()));
        }

        Map<String, List<ResolvedEndpoint>> routesByModule = group(graph.endpoints(), graph);
        Map<String, List<ResolvedEndpoint>> clientsByModule = group(graph.clientEndpoints(), graph);

        for (Map.Entry<String, TypeNode> svc : services.entrySet()) {
            String module = svc.getKey();
            TypeNode app = svc.getValue();
            List<SourceFile> files = filesByModule.getOrDefault(module, List.of());
            out.blank();
            out.line("Service " + module + " [" + buildTool(files) + "]");
            out.line("  app " + app.name() + "  " + out.ref(app.file(), app.decl().line()));
            routes(routesByModule.getOrDefault(module, List.of()), out);
            beans(graph, module, out);
            data(graph, module, out);
            messaging(graph, module, out);
            for (ResolvedEndpoint c : clientsByModule.getOrDefault(module, List.of())) {
                out.line("  calls out: " + c.owner().name() + " -> " + feignTarget(c.owner()) + "  "
                        + c.httpMethod() + " " + c.path());
            }
            configs(files, out);
        }

        List<String> others = new ArrayList<>();
        for (Map.Entry<String, List<SourceFile>> m : filesByModule.entrySet()) {
            if (!services.containsKey(m.getKey())) {
                long java = m.getValue().stream().filter(SourceFile::isJava).count();
                if (java > 0) {
                    others.add(m.getKey() + " (" + java + " Java files)");
                }
            }
        }
        if (!others.isEmpty()) {
            out.blank();
            out.line("Other modules: " + String.join(", ", others));
        }

        List<String> failed = new ArrayList<>();
        graph.files().stream().filter(f -> f.isJava() && f.parseError() != null)
                .limit(3).forEach(f -> failed.add(ws.display(f) + " (" + f.parseError() + ")"));
        out.blank();
        if (failed.isEmpty()) {
            out.line("Coverage: all " + javaFiles + " Java files parsed. Detail: endpoint_flow, messaging_map, data_map, impact_of.");
        } else {
            out.line("Coverage: " + snapshot.stats().parseErrors() + " Java files could not be parsed and are absent from answers, e.g. "
                    + String.join("; ", failed));
        }
    }

    private static Map<String, List<ResolvedEndpoint>> group(List<ResolvedEndpoint> eps, CodeGraph graph) {
        Map<String, List<ResolvedEndpoint>> out = new LinkedHashMap<>();
        for (ResolvedEndpoint e : eps) {
            out.computeIfAbsent(graph.moduleKey(e.owner().file()), k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    private static void routes(List<ResolvedEndpoint> routes, Answer out) {
        if (routes.isEmpty()) {
            out.line("  routes: none");
            return;
        }
        out.line("  routes (" + routes.size() + "):");
        Map<TypeNode, List<ResolvedEndpoint>> byController = new LinkedHashMap<>();
        routes.stream().limit(MAX_ROUTES_PER_SERVICE)
                .forEach(r -> byController.computeIfAbsent(r.owner(), k -> new ArrayList<>()).add(r));
        for (Map.Entry<TypeNode, List<ResolvedEndpoint>> c : byController.entrySet()) {
            out.line("    " + c.getKey().name() + "  " + out.ref(c.getKey().file()));
            for (ResolvedEndpoint r : c.getValue()) {
                out.line(String.format("      %-6s %s  %s :%d", r.httpMethod(), r.path(), r.handler(), r.line()));
            }
        }
        if (routes.size() > MAX_ROUTES_PER_SERVICE) {
            out.line("    +" + (routes.size() - MAX_ROUTES_PER_SERVICE) + " more routes (use locate or endpoint_flow)");
        }
    }

    private static void beans(CodeGraph graph, String module, Answer out) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        BEAN_ORDER.forEach(k -> counts.put(k, 0));
        for (TypeNode n : graph.types()) {
            String kind = graph.beanKind(n);
            if (kind != null && counts.containsKey(kind) && graph.moduleKey(n.file()).equals(module)) {
                counts.merge(kind, 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> {
            if (v > 0) {
                parts.add(k + " " + v);
            }
        });
        out.line("  beans: " + (parts.isEmpty() ? "none" : String.join(" · ", parts)));
    }

    private static void data(CodeGraph graph, String module, Answer out) {
        Integrations integ = graph.integrations();
        List<String> units = new ArrayList<>();
        for (Integrations.Entity e : integ.entities()) {
            if (graph.moduleKey(e.type().file()).equals(module)) {
                units.add(e.name());
            }
        }
        List<Integrations.Store> stores = integ.stores().getOrDefault(module, List.of());
        if (units.isEmpty() && stores.stream().noneMatch(s -> s.role().equals("datastore"))) {
            return;
        }
        out.line("  data: " + DataMapTool.storesLine(stores) + (units.isEmpty() ? "" : " · " + String.join(", ", units)));
    }

    private static void messaging(CodeGraph graph, String module, Answer out) {
        Set<String> produces = new TreeSet<>();
        Set<String> consumes = new TreeSet<>();
        for (Integrations.Message m : graph.integrations().messages()) {
            if (graph.moduleKey(m.owner().file()).equals(module)) {
                (m.producer() ? produces : consumes).add(m.system() + " " + m.channel());
            }
        }
        if (produces.isEmpty() && consumes.isEmpty()) {
            return;
        }
        out.line("  messaging: produces " + (produces.isEmpty() ? "-" : String.join(", ", produces))
                + " · consumes " + (consumes.isEmpty() ? "-" : String.join(", ", consumes)));
    }

    private static void configs(List<SourceFile> files, Answer out) {
        List<String> cfg = new ArrayList<>();
        for (SourceFile f : files) {
            String name = f.path().substring(f.path().lastIndexOf('/') + 1);
            if (name.startsWith("application") || name.startsWith("bootstrap")) {
                cfg.add(out.ref(f));
            }
        }
        if (!cfg.isEmpty()) {
            out.line("  config: " + String.join(", ", cfg));
        }
    }

    static String buildTool(List<SourceFile> moduleFiles) {
        String module = moduleFiles.isEmpty() ? "" : moduleFiles.get(0).module();
        String prefix = module.isEmpty() ? "" : module + "/";
        for (SourceFile f : moduleFiles) {
            if (f.path().equals(prefix + "build.gradle") || f.path().equals(prefix + "build.gradle.kts")) {
                return "gradle";
            }
        }
        for (SourceFile f : moduleFiles) {
            if (f.path().equals(prefix + "pom.xml")) {
                return "maven";
            }
        }
        return "unknown build";
    }

    static String feignTarget(TypeNode feignClient) {
        var ann = feignClient.decl().annotation("FeignClient");
        if (ann == null) {
            return "?";
        }
        String target = ann.firstAttr("name", "value", "url");
        return target == null ? "?" : target;
    }
}
