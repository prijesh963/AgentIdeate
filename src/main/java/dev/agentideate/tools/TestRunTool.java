package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.exec.BuildTool;
import dev.agentideate.exec.CommandRunner;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.SourceFile;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanChange;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.plan.PlanVersion;
import dev.agentideate.testing.TestAttempts;
import dev.agentideate.workspace.Workspace;
import dev.agentideate.workspace.WorkspaceService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workflow 5, second half: runs only the tests that matter (the test classes of what a plan changed,
 * or named classes) with the module's own build, and reads the JUnit reports that run wrote. A plan
 * gets {@link TestAttempts#LIMIT} failed runs; then the agent is told to stop and report.
 */
final class TestRunTool implements Tool {

    static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final ToolRegistry registry;

    TestRunTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "test_run";
    }

    @Override
    public String description() {
        return "Run only the tests of a plan's changes (or named test classes) and summarize failures with file:line. "
                + "At most 3 failed runs per plan.";
    }

    @Override
    public boolean threaded() {
        return false;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("plan").put("type", "string");
        p.putObject("tests").put("type", "string").put("description", "Test classes, comma-separated, instead of a plan");
        return s;
    }

    /** Test classes to run in one build module. */
    private record Batch(Workspace.Root root, BuildTool build, Set<TypeNode> tests) {
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        String planId = args.string("plan");
        String names = args.string("tests");
        Set<TypeNode> tests = new LinkedHashSet<>();
        Plan plan = null;
        TestAttempts attempts = null;
        try {
            if (planId != null) {
                plan = new PlanStore(snapshot.stateDir()).get(planId);
                if (plan == null) {
                    throw new ToolException("No plan '" + planId + "'. plan_show lists the plans.");
                }
                if (!plan.implemented()) {
                    throw new ToolException(plan.id + " is not implemented (" + plan.status + "); there is nothing of it to test yet.");
                }
                attempts = TestAttempts.load(snapshot.stateDir(), plan.id);
                if (attempts.failed >= TestAttempts.LIMIT) {
                    throw new ToolException("Stopped: " + plan.id + "'s tests have failed " + attempts.failed + " times ("
                            + String.join("; ", attempts.history.subList(Math.max(0, attempts.history.size() - TestAttempts.LIMIT),
                            attempts.history.size())) + "). Do not try another fix: tell the developer what fails and what was "
                            + "tried. They can allow " + TestAttempts.LIMIT + " more runs with: java -jar agentideate.jar tests "
                            + plan.id + " --reset");
                }
                testsOfPlan(graph, plan, tests);
                if (tests.isEmpty()) {
                    out.line("Not found: no test class for " + plan.id + "'s changes (searched test files the plan writes, and tests "
                            + "of each changed class by name <Class>Test/Tests/IT, fields, calls and imports). test_pack proposes one.");
                    return;
                }
            } else if (names != null) {
                for (String n : names.split(",")) {
                    tests.add(testClass(graph, n.trim()));
                }
            } else {
                throw new ToolException("Pass plan (an implemented plan) or tests (test class names).");
            }
        } catch (IOException e) {
            throw new ToolException("Could not read plans: " + e.getMessage());
        }

        List<Batch> batches = batches(snapshot, tests);
        out.line((plan == null ? "Tests " + names : "Tests for " + plan.id + " (run " + plan.implementedRun + "), attempt "
                + (attempts.failed + 1) + " of " + TestAttempts.LIMIT) + ":");
        boolean anyFailed = false;
        List<String> summary = new ArrayList<>();
        for (Batch b : batches) {
            String outcome = run(snapshot, graph, b, out);
            summary.add(b.build().moduleDir().getFileName() + " " + outcome);
            anyFailed |= outcome.equals("failed") || outcome.equals("timeout"); // a run that never started is not an attempt
        }
        if (plan != null) {
            try {
                if (anyFailed) {
                    attempts.failed++;
                }
                attempts.history.add(Instant.ofEpochMilli(registry.now()) + " run " + plan.implementedRun + ": " + String.join(", ", summary));
                attempts.save(snapshot.stateDir(), plan.id);
            } catch (IOException e) {
                out.line("(attempt count not saved: " + e.getMessage() + ")");
            }
            if (!anyFailed && summary.stream().allMatch(x -> x.endsWith(" passed"))) {
                out.line("All passed. plan_review " + plan.id + " checks the run against its plan.");
            } else if (!anyFailed) {
                out.line("The tests did not run (above): fix the build, or tell the developer; changing the code will not help. "
                        + "This did not count as a failed run.");
            } else if (attempts.failed >= TestAttempts.LIMIT) {
                out.line("That was failed run " + attempts.failed + " of " + TestAttempts.LIMIT + ". Stop here: report to the developer what "
                        + "fails and what was tried; do not change more code.");
            } else {
                out.line("Next: find the cause (failure lines above), fix it in a revised plan (plan_submit; tests or code), the developer "
                        + "approves, plan_implement, then test_run again. " + (TestAttempts.LIMIT - attempts.failed) + " failed run"
                        + (TestAttempts.LIMIT - attempts.failed == 1 ? "" : "s") + " left.");
            }
        }
    }

    /** Test classes the plan writes, and the tests of every main class it changes. */
    private static void testsOfPlan(CodeGraph graph, Plan plan, Set<TypeNode> tests) {
        PlanVersion v = plan.version(plan.approvedVersion);
        for (PlanChange c : v.changes) {
            if (c.root == null || c.path == null || "delete".equals(c.kind)) {
                continue;
            }
            for (TypeNode t : graph.types()) {
                SourceFile f = t.file();
                if (!f.root().equals(c.root) || !f.path().equals(c.path) || !topLevel(t)) {
                    continue;
                }
                if (f.isTest()) {
                    tests.add(t);
                } else {
                    graph.reverse().testsOf(t).stream().filter(TestRunTool::topLevel).forEach(tests::add);
                }
            }
        }
    }

    /** A test class by simple or qualified name; an ambiguous name resolves to nothing. */
    private static TypeNode testClass(CodeGraph graph, String name) throws ToolException {
        List<TypeNode> found = (name.contains(".") ? graph.byFqn(name) : graph.bySimpleName(name)).stream()
                .filter(t -> t.file().isTest()).toList();
        if (found.isEmpty()) {
            throw new ToolException("Not found: no test class '" + name + "' under src/test (searched simple and qualified names).");
        }
        if (found.size() > 1) {
            throw new ToolException(found.size() + " test classes are named " + name + ": "
                    + String.join(", ", found.stream().map(TypeNode::fqn).toList()) + ". Pass the fully qualified name.");
        }
        return found.get(0);
    }

    private static boolean topLevel(TypeNode t) {
        String file = t.file().path().substring(t.file().path().lastIndexOf('/') + 1);
        return file.equals(t.name() + ".java");
    }

    private static List<Batch> batches(WorkspaceService.Snapshot snapshot, Set<TypeNode> tests) throws ToolException {
        Map<Path, Batch> out = new LinkedHashMap<>();
        for (TypeNode t : tests) {
            Workspace.Root root = snapshot.workspace().root(t.file().root());
            Path module = BuildTool.moduleOf(root.path(), snapshot.workspace().absolute(t.file()));
            BuildTool build = module == null ? null : BuildTool.of(root.path(), module);
            if (build == null) {
                throw new ToolException("Not found: no build.gradle or pom.xml above " + t.file().path() + "; cannot run " + t.name() + ".");
            }
            out.computeIfAbsent(module, m -> new Batch(root, build, new LinkedHashSet<>())).tests().add(t);
        }
        return new ArrayList<>(out.values());
    }

    /** Runs one module's tests and reports; returns {@code passed}, {@code failed}, {@code timeout} or {@code not-run}. */
    private String run(WorkspaceService.Snapshot snapshot, CodeGraph graph, Batch b, Answer out) throws ToolException {
        List<String> classes = b.tests().stream().map(TypeNode::fqn).toList();
        List<String> cmd = b.build().testCommand(classes);
        String rootPrefix = b.root().path().toString() + File.separator;
        out.line("  " + String.join(" ", cmd).replace(rootPrefix, ""));
        long start = System.currentTimeMillis() - 2_000; // file times can be coarser than the clock
        CommandRunner.Result r;
        try {
            r = registry.runner().run(cmd, b.build().moduleDir(), TIMEOUT);
        } catch (IOException e) {
            out.line("  Could not run: " + e.getMessage());
            return "not-run";
        }
        if (r.timedOut()) {
            out.line("  Stopped after " + TIMEOUT.toMinutes() + " minutes.");
            return "timeout";
        }
        LogDigest digest = new LogDigest(cls -> CompressLogTool.locate(graph, out, cls),
                abs -> CompressLogTool.display(snapshot.workspace(), graph, out, abs));
        List<Path> reports = new ArrayList<>();
        b.build().reportDirs().forEach(d -> JUnitReports.collect(d, start, reports));
        if (reports.isEmpty()) {
            // No report from this run: the build stopped before the tests. Compile errors are the code's
            // fault; anything else (dependencies, build setup) is not, and is not a failed attempt.
            out.input(r.output().length(), "the test build output");
            boolean compileErrors = BuildTool.hasCompileErrors(r.output());
            out.line("  " + (r.exitCode() == 0 ? "Build passed but wrote no JUnit report (no matching tests ran?)"
                    : compileErrors ? "FAILED before any test ran: compile errors"
                    : "The build could not run the tests, for a reason other than the code (below):"));
            digest.digest(r.output()).stream().limit(LogDigest.MAX_ITEMS).forEach(l -> out.line("    " + l));
            return r.exitCode() == 0 ? "no-report" : compileErrors ? "failed" : "not-run";
        }
        JUnitReports.Summary s = JUnitReports.read(reports, digest);
        out.input(s.bytes() + r.output().length(), "the test output and " + s.reports() + " JUnit report" + (s.reports() == 1 ? "" : "s"));
        boolean passed = r.exitCode() == 0 && s.failed() == 0;
        out.line("  " + (passed ? "PASSED" : "FAILED") + ": " + s.tests() + " tests, " + s.failed() + " failed"
                + (s.skipped() > 0 ? ", " + s.skipped() + " skipped" : "") + String.format(" (%.1f s)", r.millis() / 1000.0));
        s.lines().stream().limit(LogDigest.MAX_ITEMS).forEach(l -> out.line("  " + l));
        if (!passed && s.failed() == 0) {
            digest.digest(r.output()).stream().limit(10).forEach(l -> out.line("    " + l));
        }
        return passed ? "passed" : "failed";
    }
}
