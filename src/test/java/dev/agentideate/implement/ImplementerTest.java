package dev.agentideate.implement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import dev.agentideate.exec.CommandRunner;
import dev.agentideate.plan.Plan;
import dev.agentideate.plan.PlanService;
import dev.agentideate.plan.PlanStore;
import dev.agentideate.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImplementerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String IMPL = "order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java";
    static final String TOTAL = "        order.setTotal(total(price.amount(), request.quantity()));";
    static final String NEW_TOTAL = "        order.setTotal(total(price.amount(), request.quantity()).negate());";
    static final String ADDED = "order-service/src/main/java/com/shop/order/discount/DiscountPolicy.java";
    static final String ADDED_CONTENT = "package com.shop.order.discount;\n\npublic class DiscountPolicy {\n}\n";

    /** Records commands; git is "not a work tree" and builds answer with {@link #build}. */
    static final class FakeRunner implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        Function<List<String>, Result> build = cmd -> new Result(0, "BUILD SUCCESSFUL", false, 5);

        @Override
        public Result run(List<String> command, Path workDir, Duration timeout) {
            commands.add(command);
            if (command.get(0).equals("git")) {
                return new Result(128, "fatal: not a git repository", false, 1);
            }
            return build.apply(command);
        }
    }

    @TempDir
    Path tmp;

    Path repo;
    WorkspaceService workspaces;
    FakeRunner runner = new FakeRunner();

    @BeforeEach
    void setUp() throws Exception {
        repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        workspaces = new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null);
    }

    private Plan approvedPlan(ObjectNode... changes) throws Exception {
        ObjectNode p = JSON.createObjectNode().put("title", "Discount").put("understanding", "Negate the total, for the test.");
        var arr = p.putArray("changes");
        for (ObjectNode c : changes) {
            arr.add(c);
        }
        PlanService s = new PlanService(workspaces.snapshot(null), 10L);
        Plan plan = s.submit(p);
        assertTrue(plan.latest().errors.isEmpty(), plan.latest().errors.toString());
        s.approve(plan, plan.latest(), "test");
        return s.store().get(plan.id);
    }

    static ObjectNode edit(String file, String before, String after) {
        return JSON.createObjectNode().put("kind", "edit").put("file", file).put("before", before).put("after", after);
    }

    static ObjectNode add(String file, String content) {
        return JSON.createObjectNode().put("kind", "add").put("file", file).put("after", content);
    }

    static ObjectNode delete(String file) {
        return JSON.createObjectNode().put("kind", "delete").put("file", file);
    }

    private Implementer.Outcome implement(Plan plan, boolean compile) throws Exception {
        return new Implementer(workspaces.snapshot(null), runner, 20L).implement(plan, "test", compile, false, Duration.ofMinutes(1));
    }

    private String read(String rel) throws IOException {
        return Files.readString(repo.resolve(rel), StandardCharsets.UTF_8);
    }

    @Test
    void anApprovedPlanIsWrittenExactlyVerifiedAndRecorded() throws Exception {
        String original = read(IMPL);
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL), add(ADDED, ADDED_CONTENT));
        Implementer.Outcome o = implement(plan, false);
        assertTrue(o.applied(), o.problems().toString());
        assertEquals(original.replace(TOTAL, NEW_TOTAL), read(IMPL));
        assertEquals(ADDED_CONTENT, read(ADDED));
        assertTrue(o.run().verify.stream().allMatch(v -> v.startsWith("ok   ")), o.run().verify.toString());
        assertEquals("skipped", o.run().compileStatus);

        Plan stored = new PlanStore(workspaces.snapshot(null).stateDir()).get(plan.id);
        assertTrue(stored.implemented());
        assertEquals("r1", stored.implementedRun);
        Path stateDir = workspaces.snapshot(null).stateDir();
        assertTrue(Files.isRegularFile(stateDir.resolve("runs/r1/run.json")));
        assertEquals(original, Files.readString(stateDir.resolve("runs/r1/backup/shop/" + IMPL)), "the original is backed up");
        assertTrue(Files.readString(stateDir.resolve("audit.jsonl")).contains("\"event\":\"applied\""));
        assertTrue(o.run().outsidePlan.get(0).contains("not a git work tree"), o.run().outsidePlan.toString());
    }

    @Test
    void aWindowsFileKeepsItsLineEndings() throws Exception {
        Path impl = repo.resolve(IMPL);
        Files.writeString(impl, Files.readString(impl).replace("\n", "\r\n"));
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL + "\n        // discounted"));
        assertTrue(implement(plan, false).applied());
        String now = read(IMPL);
        assertTrue(now.contains(NEW_TOTAL + "\r\n        // discounted\r\n"), "new lines take the file's CRLF");
        assertFalse(now.replace("\r\n", "").contains("\n"), "no bare LF anywhere");
    }

    @Test
    void nothingIsWrittenWithoutAnApprovalForTheLatestVersion() throws Exception {
        PlanService s = new PlanService(workspaces.snapshot(null), 10L);
        ObjectNode p = JSON.createObjectNode().put("title", "x").put("understanding", "x");
        p.putArray("changes").add(edit(IMPL, TOTAL, NEW_TOTAL));
        Plan draft = s.submit(p);
        Implementer.Outcome o = implement(draft, false);
        assertNull(o.run());
        assertTrue(o.problems().get(0).contains("has no approved latest version"), o.problems().toString());
        assertTrue(read(IMPL).contains(TOTAL));
    }

    @Test
    void aFileChangedAfterApprovalStopsEverything() throws Exception {
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL), add(ADDED, ADDED_CONTENT));
        Path impl = repo.resolve(IMPL);
        Files.writeString(impl, Files.readString(impl) + "\n// someone else's edit\n");
        Implementer.Outcome o = implement(plan, false);
        assertNull(o.run());
        assertTrue(o.problems().get(0).startsWith("These files changed after the plan was approved"), o.problems().toString());
        assertFalse(Files.exists(repo.resolve(ADDED)), "not even the new file is written");
    }

    @Test
    void aFailureHalfwayRestoresEveryFileAlreadyWritten() throws Exception {
        String original = read(IMPL);
        // the second change can only fail when written: its parent folder is a file
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL), add("order-service/build.gradle/notes.txt", "x"));
        Implementer.Outcome o = implement(plan, false);
        assertFalse(o.applied());
        assertEquals("rolled_back", o.run().status);
        assertTrue(o.problems().get(0).startsWith("Writing failed"), o.problems().toString());
        assertEquals(original, read(IMPL), "the edit that succeeded was undone");
        assertTrue(Files.readString(workspaces.snapshot(null).stateDir().resolve("audit.jsonl")).contains("rolled_back"));
        assertFalse(new PlanStore(workspaces.snapshot(null).stateDir()).get(plan.id).implemented());
    }

    @Test
    void codeThatDoesNotCompileIsRolledBack() throws Exception {
        String original = read(IMPL);
        runner.build = cmd -> new CommandRunner.Result(1, repo.resolve(IMPL) + ":38: error: cannot find symbol\n"
                + "  symbol:   method negate()\n1 error\nBUILD FAILED in 2s\n", false, 2000);
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL));
        Implementer.Outcome o = implement(plan, true);
        assertFalse(o.applied());
        assertEquals("failed", o.run().compileStatus);
        assertEquals(original, read(IMPL));
        assertTrue(o.compileDigest().stream().anyMatch(l -> l.contains(IMPL + ":38  cannot find symbol")), o.compileDigest().toString());
        List<String> build = runner.commands.stream().filter(c -> !c.get(0).equals("git")).findFirst().orElseThrow();
        assertEquals(List.of("gradle", "-p", repo.resolve("order-service").toString(), "compileJava", "compileTestJava", "--console=plain"), build);
    }

    @Test
    void aBuildThatFailsForAnotherReasonKeepsTheChanges() throws Exception {
        runner.build = cmd -> new CommandRunner.Result(1, "Could not resolve org.springframework.boot:spring-boot-starter-web\nBUILD FAILED\n", false, 100);
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL));
        Implementer.Outcome o = implement(plan, true);
        assertTrue(o.applied());
        assertEquals("not-run", o.run().compileStatus);
        assertTrue(read(IMPL).contains(NEW_TOTAL));
    }

    @Test
    void aPassingCompileUsesTheWrapperAndMavenModulesUseMaven() throws Exception {
        Files.writeString(repo.resolve("order-service/gradlew"), "#!/bin/sh\n");
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL), edit("inventory-service/src/main/java/com/shop/inventory/service/InventoryService.java",
                "    public StockItem stockFor(String sku) {", "    public StockItem stockFor(String sku) { // checked"));
        Implementer.Outcome o = implement(plan, true);
        assertTrue(o.applied());
        assertEquals("passed", o.run().compileStatus);
        List<List<String>> builds = runner.commands.stream().filter(c -> !c.get(0).equals("git")).toList();
        assertEquals(repo.resolve("order-service/gradlew").toString(), builds.get(0).get(0));
        assertEquals(List.of("mvn", "-q", "-B", "-f", repo.resolve("inventory-service/pom.xml").toString(), "test-compile"), builds.get(1));
    }

    @Test
    void undoOfARun() throws Exception {
        String original = read(IMPL);
        Files.writeString(repo.resolve("order-service/src/main/java/com/shop/order/Unused.java"), "package com.shop.order;\nclass Unused {}\n");
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL), add(ADDED, ADDED_CONTENT),
                delete("order-service/src/main/java/com/shop/order/Unused.java"));
        Implementer impl = new Implementer(workspaces.snapshot(null), runner, 20L);
        Implementer.Outcome o = impl.implement(plan, "test", false, false, Duration.ofMinutes(1));
        assertTrue(o.applied(), o.problems().toString());
        assertFalse(Files.exists(repo.resolve("order-service/src/main/java/com/shop/order/Unused.java")));

        List<String> skipped = new Implementer(workspaces.snapshot(null), runner, 30L).undo(impl.load("r1"));
        assertTrue(skipped.isEmpty(), skipped.toString());
        assertEquals(original, read(IMPL));
        assertFalse(Files.exists(repo.resolve(ADDED)));
        assertFalse(Files.exists(repo.resolve("order-service/src/main/java/com/shop/order/discount")), "the folder it created is gone");
        assertTrue(Files.exists(repo.resolve("order-service/src/main/java/com/shop/order/Unused.java")), "the deleted file is back");
        Plan stored = new PlanStore(workspaces.snapshot(null).stateDir()).get(plan.id);
        assertFalse(stored.implemented());
        assertTrue(stored.approved(), "undo returns the plan to approved");
    }

    @Test
    void undoLeavesAFileEditedSinceTheRunAlone() throws Exception {
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL), add(ADDED, ADDED_CONTENT));
        Implementer impl = new Implementer(workspaces.snapshot(null), runner, 20L);
        assertTrue(impl.implement(plan, "test", false, false, Duration.ofMinutes(1)).applied());
        Path impl1 = repo.resolve(IMPL);
        Files.writeString(impl1, Files.readString(impl1) + "// later work\n");
        List<String> skipped = new Implementer(workspaces.snapshot(null), runner, 30L).undo(impl.load("r1"));
        assertEquals(List.of(IMPL + " (changed since the run)"), skipped);
        assertTrue(read(IMPL).endsWith("// later work\n"), "later work survives");
        assertFalse(Files.exists(repo.resolve(ADDED)), "untouched files are still undone");
    }

    @Test
    void anAlreadyImplementedPlanIsNotWrittenTwice() throws Exception {
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL));
        assertTrue(implement(plan, false).applied());
        Plan stored = new PlanStore(workspaces.snapshot(null).stateDir()).get(plan.id);
        Implementer.Outcome again = implement(stored, false);
        assertNull(again.run());
        assertTrue(again.problems().get(0).contains("already implemented (run r1)"), again.problems().toString());
    }

    @Test
    void compileErrorsAreToldApartFromOtherBuildFailures() {
        assertTrue(CompileCheck.hasCompileErrors("/x/A.java:3: error: ';' expected"));
        assertTrue(CompileCheck.hasCompileErrors("[ERROR] /x/A.java:[3,5] cannot find symbol"));
        assertFalse(CompileCheck.hasCompileErrors("Could not resolve all files for configuration ':compileClasspath'."));
    }

    @Test
    void realGitSeesOnlyThePlansFilesAndIgnoresChangesThatWereThereBefore() throws Exception {
        // the repo is a subfolder of the git work tree, so paths come with a prefix to strip
        Path top = tmp.resolve("work");
        Path nested = TestSupport.copyTree(TestSupport.shop(), top.resolve("services/shop"));
        for (List<String> cmd : List.of(List.of("git", "init", "-q"), List.of("git", "add", "-A"),
                List.of("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "base"))) {
            assertEquals(0, new ProcessBuilder(cmd).directory(top.toFile()).start().waitFor(), cmd.toString());
        }
        Files.writeString(nested.resolve("notes.txt"), "an unrelated uncommitted file, there before the run");
        workspaces = new WorkspaceService(new AppHome(tmp.resolve("home2")), List.of(nested), null);
        repo = nested;
        Plan plan = approvedPlan(edit(IMPL, TOTAL, NEW_TOTAL), add(ADDED, ADDED_CONTENT));
        Implementer.Outcome o = new Implementer(workspaces.snapshot(null), new dev.agentideate.exec.ProcessCommandRunner(), 20L)
                .implement(plan, "test", false, false, Duration.ofMinutes(1));
        assertTrue(o.applied(), o.problems().toString());
        assertEquals(List.of(), o.run().outsidePlan, "only the plan's own files changed during the run");
    }
}
