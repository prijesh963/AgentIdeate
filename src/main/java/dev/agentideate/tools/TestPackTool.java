package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.Ann;
import dev.agentideate.index.CallSite;
import dev.agentideate.index.FieldDecl;
import dev.agentideate.index.InjectionPoint;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.plan.PlanVersion;
import dev.agentideate.review.ChangedCode;
import dev.agentideate.testing.TestConventions;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workflow 5: what the agent needs to write unit tests in one go, without exploring. For each
 * method a plan changed (or a named class or method): its code, the collaborators to mock, the
 * existing test class to extend (or where a new one goes), and the module's test conventions
 * with one existing test to copy the style from. The tests themselves are written through a plan.
 */
final class TestPackTool implements Tool {

    static final int MAX_TARGETS = 6;
    static final int MAX_BODY_LINES = 40;
    static final int MAX_EXAMPLE_LINES = 25;

    @Override
    public String name() {
        return "test_pack";
    }

    @Override
    public String description() {
        return "Everything needed to write unit tests for an implemented plan's changed methods, or a Class / Class#method: "
                + "code, mocks, test class, team conventions, an example.";
    }

    @Override
    public boolean threaded() {
        return false;
    }

    @Override
    public int budgetChars() {
        return 12_000;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("plan").put("type", "string");
        p.putObject("target").put("type", "string").put("description", "Class or Class#method, instead of a plan");
        return s;
    }

    private record Item(TypeNode owner, MethodDecl method, String change) {
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        String planId = args.string("plan");
        String target = args.string("target");
        List<Item> items = new ArrayList<>();
        String heading;
        if (planId != null) {
            Plan plan;
            try {
                plan = new PlanStore(snapshot.stateDir()).get(planId);
            } catch (IOException e) {
                throw new ToolException("Could not read plans: " + e.getMessage());
            }
            if (plan == null) {
                throw new ToolException("No plan '" + planId + "'. plan_show lists the plans.");
            }
            if (!plan.implemented()) {
                throw new ToolException(plan.id + " is not implemented yet (" + plan.status + "); its changed methods are found in the "
                        + "written code. Implement it first, or pass target: Class#method.");
            }
            PlanVersion v = plan.version(plan.approvedVersion);
            for (ChangedCode.Target t : ChangedCode.methods(graph, ChangedCode.regions(snapshot.workspace(), graph, v))) {
                if (!t.owner().file().isTest()) {
                    items.add(new Item(t.owner(), t.method(), t.change()));
                }
            }
            heading = "Tests for " + plan.id + " v" + v.number + " \"" + plan.title + "\"";
            if (items.isEmpty()) {
                out.line(heading + ": Not found: no main-code method contains one of its changes (searched the new text of each "
                        + "change in today's files). Changes to fields, config or tests only need no new unit test; pass target to choose one.");
                return;
            }
        } else if (target != null) {
            heading = "Tests for " + target;
            resolve(graph, target, items);
        } else {
            throw new ToolException("Pass plan (an implemented plan) or target (Class or Class#method).");
        }

        out.line(heading + ":");
        Map<String, TestConventions> conventions = new LinkedHashMap<>();
        for (Item it : items) {
            String key = it.owner().file().root() + ":" + it.owner().file().module();
            conventions.computeIfAbsent(key, k -> TestConventions.detect(graph, it.owner().file().root(), it.owner().file().module()));
        }
        for (Map.Entry<String, TestConventions> e : conventions.entrySet()) {
            TestConventions c = e.getValue();
            String module = e.getKey().substring(e.getKey().indexOf(':') + 1);
            out.line("Conventions in " + (module.isEmpty() ? "the root module" : module) + " (from " + c.source() + "): "
                    + (c.style().isEmpty() ? "Not found: no test framework in the tests or build; ask the developer before adding one."
                    : String.join(", ", c.style())) + "; classes named *" + c.suffix());
            if (c.example() != null) {
                out.line("  Example to copy: " + c.exampleType().name() + "." + c.example().name() + "  "
                        + out.ref(c.exampleType().file(), c.example().line()));
                code(snapshot, c.exampleType(), c.example(), MAX_EXAMPLE_LINES, out);
            }
        }
        int shown = 0;
        for (Item it : items) {
            if (shown++ == MAX_TARGETS) {
                out.line("+" + (items.size() - MAX_TARGETS) + " more methods; ask for them by target: Class#method.");
                break;
            }
            TypeNode owner = it.owner();
            MethodDecl m = it.method();
            out.line("Target: " + owner.name() + "." + m.signature() + "  " + out.ref(owner.file(), m.line()) + "-" + m.endLine()
                    + (it.change() == null ? "" : " (" + it.change() + ")"));
            List<String> mocks = mocks(owner, m);
            out.line("  mock: " + (mocks.isEmpty() ? "nothing injected is called" : String.join(", ", mocks)));
            List<TypeNode> tests = graph.reverse().testsOf(owner);
            TypeNode existing = tests.stream().filter(t -> t.name().startsWith(owner.name())).findFirst()
                    .orElse(tests.isEmpty() ? null : tests.get(0));
            if (existing != null) {
                List<String> testMethods = existing.decl().methods().stream()
                        .filter(x -> x.annotations().stream().anyMatch(a -> a.name().endsWith("Test"))).map(MethodDecl::name).toList();
                List<String> setup = existing.decl().fields().stream()
                        .filter(f -> !f.annotations().isEmpty())
                        .map(f -> "@" + String.join(" @", f.annotations().stream().map(Ann::name).toList()) + " " + f.type() + " " + f.name())
                        .toList();
                out.line("  test class: " + existing.name() + "  " + out.ref(existing.file()) + " (add to it)");
                if (!setup.isEmpty()) {
                    out.line("    fields: " + String.join("; ", setup));
                }
                out.line("    has: " + (testMethods.isEmpty() ? "no test methods" : String.join(", ", testMethods)));
            } else {
                TestConventions c = conventions.get(owner.file().root() + ":" + owner.file().module());
                out.line("  test class: none yet; new file " + newTestPath(snapshot, owner, c.suffix()));
            }
            code(snapshot, owner, m, MAX_BODY_LINES, out);
        }
        out.line("Write the tests through a plan: plan_submit (add the test file, or edit the test class), the developer approves, "
                + "plan_implement, then test_run.");
    }

    /** A class (simple or fully qualified name) or Class#method; an ambiguous simple name resolves to nothing. */
    private static void resolve(CodeGraph graph, String target, List<Item> items) throws ToolException {
        String cls = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
        String method = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;
        List<TypeNode> found = cls.contains(".") ? graph.byFqn(cls) : graph.bySimpleName(cls);
        found = found.stream().filter(t -> !t.file().isTest()).toList();
        if (found.isEmpty()) {
            throw new ToolException("Not found: no main-code class '" + cls + "' in the index (searched simple and qualified names; tests excluded).");
        }
        if (found.size() > 1) {
            throw new ToolException(found.size() + " classes are named " + cls + ": "
                    + String.join(", ", found.stream().map(TypeNode::fqn).toList()) + ". Pass the fully qualified name.");
        }
        TypeNode t = found.get(0);
        for (MethodDecl m : t.decl().methods()) {
            if (method == null || m.name().equals(method)) {
                items.add(new Item(t, m, null));
            }
        }
        if (items.isEmpty()) {
            throw new ToolException("Not found: " + t.name() + " has no " + (method == null ? "methods" : "method '" + method + "'")
                    + " (" + t.file().path() + ").");
        }
    }

    /** Injected collaborators the method calls: what a unit test mocks. */
    static List<String> mocks(TypeNode owner, MethodDecl m) {
        List<String> out = new ArrayList<>();
        Map<String, String> byType = new LinkedHashMap<>();
        for (InjectionPoint ip : owner.decl().injections()) {
            byType.putIfAbsent(raw(ip.type()), ip.type() + " " + ip.name());
        }
        for (FieldDecl f : owner.decl().fields()) {
            if (f.annotations().stream().anyMatch(a -> a.name().equals("Autowired"))) {
                byType.putIfAbsent(raw(f.type()), f.type() + " " + f.name());
            }
        }
        for (CallSite c : m.calls()) {
            String hit = c.scopeType() == null ? null : byType.get(raw(c.scopeType()));
            if (hit != null && !out.contains(hit)) {
                out.add(hit);
            }
        }
        return out;
    }

    private static String raw(String type) {
        int lt = type.indexOf('<');
        return lt < 0 ? type : type.substring(0, lt);
    }

    private static String newTestPath(WorkspaceService.Snapshot snapshot, TypeNode owner, String suffix) {
        String path = owner.file().path();
        int main = path.indexOf("src/main/java/");
        String base = main >= 0 ? path.substring(0, main) + "src/test/java/" + path.substring(main + "src/main/java/".length())
                : path;
        base = base.substring(0, base.lastIndexOf('/') + 1) + owner.name() + suffix + ".java";
        return snapshot.workspace().multiRoot() ? owner.file().root() + "/" + base : base;
    }

    private static void code(WorkspaceService.Snapshot snapshot, TypeNode owner, MethodDecl m, int max, Answer out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(snapshot.workspace().absolute(owner.file()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            out.line("    (could not read " + owner.file().path() + ": " + e.getMessage() + ")");
            return;
        }
        int first = Math.max(1, m.line());
        int last = Math.min(lines.size(), m.endLine());
        int end = Math.min(last, first + max - 1);
        for (int i = first; i <= end; i++) {
            out.line(String.format("    %4d | %s", i, lines.get(i - 1)));
        }
        if (end < last) {
            out.line("         ... " + (last - end) + " more lines");
        }
    }
}
