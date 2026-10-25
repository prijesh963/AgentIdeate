package dev.agentideate.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import dev.agentideate.exec.CommandRunner;
import dev.agentideate.testing.TestAttempts;
import dev.agentideate.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow 5: the test pack, and running only the affected tests with a cap on failed runs. */
class TestToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String IMPL = "order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java";
    static final String TEST = "order-service/src/test/java/com/shop/order/service/OrderServiceImplTest.java";
    static final String TOTAL = "        order.setTotal(total(price.amount(), request.quantity()));";
    static final String NEW_TOTAL = "        order.setTotal(total(price.amount(), request.quantity()).negate());";
    static final String PASSING = """
            <testsuite name="com.shop.order.service.OrderServiceImplTest" tests="2">
              <testcase classname="com.shop.order.service.OrderServiceImplTest" name="findOrderReturnsRepositoryResult"/>
              <testcase classname="com.shop.order.service.OrderServiceImplTest" name="placeOrderTotals"/>
            </testsuite>
            """;
    static final String FAILING = """
            <testsuite name="com.shop.order.service.OrderServiceImplTest" tests="2">
              <testcase classname="com.shop.order.service.OrderServiceImplTest" name="findOrderReturnsRepositoryResult"/>
              <testcase classname="com.shop.order.service.OrderServiceImplTest" name="placeOrderTotals">
                <failure message="expected: 20 but was: -20" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: expected: 20 but was: -20
                at com.shop.order.service.OrderServiceImplTest.placeOrderTotals(OrderServiceImplTest.java:30)
                </failure>
              </testcase>
            </testsuite>
            """;

    @TempDir
    Path tmp;

    Path repo;
    ToolRegistry registry;
    PlanToolsTest.ScriptedClient client = new PlanToolsTest.ScriptedClient();
    final List<List<String>> commands = new ArrayList<>();
    /** What the fake test build does: writes a report (or not) and answers. */
    Function<List<String>, CommandRunner.Result> testBuild = cmd -> report(FAILING, 1);

    @BeforeEach
    void setUp() throws Exception {
        repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        registry = ToolRegistry.standard(new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null));
        registry.client(client);
        registry.runner((cmd, dir, timeout) -> {
            commands.add(cmd);
            if (cmd.get(0).equals("git")) {
                return new CommandRunner.Result(128, "not a repo", false, 1);
            }
            return cmd.contains("test") ? testBuild.apply(cmd) : new CommandRunner.Result(0, "BUILD SUCCESSFUL", false, 1);
        });
    }

    private CommandRunner.Result report(String xml, int exit) {
        try {
            Path dir = repo.resolve("order-service/build/test-results/test");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("TEST-com.shop.order.service.OrderServiceImplTest.xml"), xml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new CommandRunner.Result(exit, exit == 0 ? "BUILD SUCCESSFUL" : "BUILD FAILED", false, 2_100);
    }

    private static ObjectNode answer(String decision) {
        ObjectNode a = JSON.createObjectNode().put("action", "accept");
        a.putObject("content").put("decision", decision);
        return a;
    }

    private static ObjectNode plan() {
        return JSON.createObjectNode().put("plan", "p1");
    }

    private void implement() {
        ObjectNode p = JSON.createObjectNode().put("title", "Negate").put("understanding", "Negate the total.");
        p.putArray("changes").addObject().put("kind", "edit").put("file", IMPL).put("before", TOTAL).put("after", NEW_TOTAL);
        assertFalse(registry.call("plan_submit", p).error());
        client.answers.add(answer("approve"));
        client.answers.add(answer("start"));
        registry.call("plan_approve", plan());
        assertTrue(registry.call("plan_implement", plan()).text().startsWith("Implemented p1 v1"));
    }

    @Test
    void thePackForAPlanHasTheChangedMethodItsMocksTestClassConventionsAndAnExample() {
        implement();
        String t = registry.call("test_pack", plan()).text();
        assertTrue(t.startsWith("Tests for p1 v1 \"Negate\":\n"), t);
        assertTrue(t.contains("Conventions in order-service (from 1 existing test class): JUnit 5, "
                + "Mockito (@ExtendWith(MockitoExtension.class), @Mock/@InjectMocks), AssertJ assertThat; classes named *Test"), t);
        assertTrue(t.contains("  Example to copy: OrderServiceImplTest.findOrderReturnsRepositoryResult  " + TEST + ":"), t);
        assertTrue(t.contains("assertThat(orderService.findOrder(1L)).contains(order);"), t);
        assertTrue(t.contains("Target: OrderServiceImpl.OrderEntity placeOrder(OrderRequest request)  " + IMPL + ":30-"), t);
        assertTrue(t.contains("  mock: PricingClient pricingClient, OrderRepository orderRepository, OrderEventPublisher eventPublisher\n"), t);
        assertTrue(t.contains("  test class: OrderServiceImplTest  " + TEST + " (add to it)\n"), t);
        assertTrue(t.contains("    fields: @Mock OrderRepository orderRepository; @InjectMocks OrderServiceImpl orderService\n"), t);
        assertTrue(t.contains("    has: findOrderReturnsRepositoryResult\n"), t);
        assertTrue(t.contains("      38 | " + NEW_TOTAL), t);
        assertFalse(t.contains("findOrder(Long id)  "), "only the changed method is a target");
        assertTrue(t.contains("Write the tests through a plan: plan_submit"), t);
    }

    @Test
    void aModuleWithoutTestsUsesWhatItsBuildProvidesAndProposesWhereTheTestGoes() {
        String t = registry.call("test_pack", JSON.createObjectNode().put("target", "ShipmentService")).text();
        assertTrue(t.contains("Conventions in shipping-service (from no tests yet; the test dependencies of shipping-service/build.gradle): "
                + "JUnit 5, Mockito, AssertJ assertThat; classes named *Test"), t);
        assertTrue(t.contains("  test class: none yet; new file shipping-service/src/test/java/com/shop/shipping/service/ShipmentServiceTest.java"), t);
        assertFalse(t.contains("Example to copy"), t);
    }

    @Test
    void aModuleWithNoTestFrameworkSaysSoInsteadOfInventingOne() {
        String t = registry.call("test_pack", JSON.createObjectNode().put("target", "InventoryService")).text();
        assertTrue(t.contains("Conventions in inventory-service (from no tests yet; the test dependencies of inventory-service/pom.xml): "
                + "Not found: no test framework in the tests or build; ask the developer before adding one."), t);
    }

    @Test
    void thePackNeedsAnImplementedPlanOrAKnownTarget() {
        ObjectNode p = JSON.createObjectNode().put("title", "Negate").put("understanding", "Negate the total.");
        p.putArray("changes").addObject().put("kind", "edit").put("file", IMPL).put("before", TOTAL).put("after", NEW_TOTAL);
        registry.call("plan_submit", p);
        ToolRegistry.Outcome o = registry.call("test_pack", plan());
        assertTrue(o.error());
        assertTrue(o.text().startsWith("p1 is not implemented yet (draft)"), o.text());
        o = registry.call("test_pack", JSON.createObjectNode().put("target", "NoSuchService#x"));
        assertTrue(o.text().startsWith("Not found: no main-code class 'NoSuchService'"), o.text());
        o = registry.call("test_pack", JSON.createObjectNode().put("target", "OrderServiceImpl#nope"));
        assertTrue(o.text().startsWith("Not found: OrderServiceImpl has no method 'nope'"), o.text());
    }

    @Test
    void onlyThePlansTestsRunAndAFailureIsReadFromTheJUnitReport() {
        implement();
        String t = registry.call("test_run", plan()).text();
        List<String> cmd = commands.get(commands.size() - 1);
        assertEquals(List.of("gradle", "-p", repo.resolve("order-service").toString(), "test", "--tests",
                "com.shop.order.service.OrderServiceImplTest", "--console=plain"), cmd);
        assertTrue(t.startsWith("Tests for p1 (run r1), attempt 1 of 3:\n  gradle -p order-service test --tests "
                + "com.shop.order.service.OrderServiceImplTest --console=plain\n"), t);
        assertTrue(t.contains("  FAILED: 2 tests, 1 failed (2.1 s)\n"), t);
        assertTrue(t.contains("    FAIL OrderServiceImplTest.placeOrderTotals  expected: 20 but was: -20  at OrderServiceImplTest.placeOrderTotals " + TEST + ":30\n"), t);
        assertTrue(t.contains("2 failed runs left."), t);
    }

    @Test
    void afterThreeFailedRunsTheAgentIsStoppedUntilTheDeveloperResets() throws Exception {
        implement();
        registry.call("test_run", plan());
        registry.call("test_run", plan());
        String third = registry.call("test_run", plan()).text();
        assertTrue(third.contains("attempt 3 of 3"), third);
        assertTrue(third.contains("That was failed run 3 of 3. Stop here: report to the developer"), third);
        int ran = commands.size();
        ToolRegistry.Outcome fourth = registry.call("test_run", plan());
        assertTrue(fourth.error());
        assertTrue(fourth.text().startsWith("Stopped: p1's tests have failed 3 times"), fourth.text());
        assertTrue(fourth.text().contains("java -jar agentideate.jar tests p1 --reset"), fourth.text());
        assertEquals(ran, commands.size(), "nothing ran");

        TestAttempts.reset(new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null).snapshot(null).stateDir(), "p1");
        testBuild = cmd -> report(PASSING, 0);
        String after = registry.call("test_run", plan()).text();
        assertTrue(after.contains("attempt 1 of 3"), after);
        assertTrue(after.contains("  PASSED: 2 tests, 0 failed"), after);
        assertTrue(after.contains("All passed. plan_review p1 checks the run against its plan."), after);
    }

    @Test
    void aTestBuildThatFailsBeforeTheTestsShowsTheCompileError() throws Exception {
        implement();
        String abs = repo.resolve(TEST).toString();
        testBuild = cmd -> new CommandRunner.Result(1, abs + ":30: error: cannot find symbol\nBUILD FAILED in 2s\n", false, 900);
        String t = registry.call("test_run", plan()).text();
        assertTrue(t.contains("  FAILED before any test ran: compile errors"), t);
        assertTrue(t.contains(TEST + ":30"), t);
        assertTrue(t.contains("cannot find symbol"), t);
        assertTrue(t.contains("2 failed runs left."), "compile errors are the code's: a failed run");
    }

    @Test
    void aBuildThatCannotResolveDependenciesIsNotAFailedRunOfThePlan() {
        implement();
        testBuild = cmd -> new CommandRunner.Result(1, """
                * What went wrong:
                Execution failed for task ':compileJava'.
                > Could not resolve all files for configuration ':compileClasspath'.
                BUILD FAILED in 1s
                """, false, 900);
        String t = registry.call("test_run", plan()).text();
        assertTrue(t.contains("  The build could not run the tests, for a reason other than the code (below):"), t);
        assertTrue(t.contains("Could not resolve all files"), t);
        assertTrue(t.contains("changing the code will not help. This did not count as a failed run."), t);
        assertTrue(registry.call("test_run", plan()).text().contains("attempt 1 of 3"), "still the first attempt");
    }

    @Test
    void reportsFromAnEarlierRunAreNotReadAsThisOnes() throws Exception {
        implement();
        report(FAILING, 1);
        Path old = repo.resolve("order-service/build/test-results/test/TEST-com.shop.order.service.OrderServiceImplTest.xml");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        testBuild = cmd -> new CommandRunner.Result(0, "BUILD SUCCESSFUL", false, 500);
        String t = registry.call("test_run", plan()).text();
        assertTrue(t.contains("Build passed but wrote no JUnit report"), t);
        assertTrue(t.contains("This did not count as a failed run."), t);
        assertFalse(t.contains("placeOrderTotals"), t);
    }

    @Test
    void namedTestClassesRunWithoutAPlanAndUnknownNamesAreNotGuessed() {
        testBuild = cmd -> report(PASSING, 0);
        String t = registry.call("test_run", JSON.createObjectNode().put("tests", "OrderServiceImplTest")).text();
        assertTrue(t.startsWith("Tests OrderServiceImplTest:\n"), t);
        assertTrue(t.contains("  PASSED: 2 tests, 0 failed"), t);
        ToolRegistry.Outcome o = registry.call("test_run", JSON.createObjectNode().put("tests", "OrderServiceTest"));
        assertTrue(o.error());
        assertTrue(o.text().startsWith("Not found: no test class 'OrderServiceTest' under src/test"), o.text());
    }
}
