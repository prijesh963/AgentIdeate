package dev.agentideate.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import dev.agentideate.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conversation threads end to end, through the registry, on a copy of the sample. */
class ThreadsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    Path repo;
    ToolRegistry registry;
    long now = 1_800_000_000_000L;

    @BeforeEach
    void setUp() throws Exception {
        repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        registry = ToolRegistry.standard(new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null));
        registry.clock(new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now);
            }
        });
    }

    private String ask(String tool, String... kv) {
        ObjectNode args = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        now += 1_000; // questions are asked one after another, as in a real chat
        ToolRegistry.Outcome o = registry.call(tool, args);
        assertFalse(o.error(), o.text());
        return o.text();
    }

    private static String footer(String answer) {
        return answer.lines().reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void aFollowUpContinuesTheThreadAndCitesKnownFilesByName() {
        String first = ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        assertTrue(footer(first).contains("thread t1 new"), footer(first));
        String follow = ask("impact_of", "target", "OrderServiceImpl#cancelOrder");
        assertTrue(footer(follow).contains("thread t1 continued (2 questions so far)"), footer(follow));
        assertTrue(follow.contains("  OrderController#cancelOrder  OrderController.java:39"), "known file by name: " + follow);
        assertTrue(follow.contains("order-service/src/test/java/com/shop/order/service/OrderServiceImplTest.java:16"),
                "a file new to the thread keeps its full path: " + follow);
    }

    @Test
    void askingAgainDoesNotRepeatAndSaysHowToGetItAll() {
        ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        String again = ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        assertTrue(again.contains("OrderController#cancelOrder  OrderController.java:37  (expanded earlier in this thread)"), again);
        assertTrue(again.contains("ask again with thread: \"new\""), again);
        String fresh = ask("endpoint_flow", "path", "/api/orders/{id}/cancel", "thread", "new");
        assertTrue(fresh.contains("OrderServiceImpl#cancelOrder  order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:45"), fresh);
        assertTrue(footer(fresh).contains("thread t2 new"), footer(fresh));
    }

    @Test
    void repeatedListLinesAreNotRepeatedAndTheNextHitsTakeTheirPlace() {
        String first = ask("locate", "query", "cancel order");
        String again = ask("locate", "query", "order cancelled");
        assertTrue(again.contains("already given earlier in this thread"), again);
        // compare hits without their path, which is shortened for files the thread already cited
        java.util.function.Function<String, String> hit = l -> l.substring(0, l.lastIndexOf("  "));
        java.util.Set<String> firstHits = new java.util.HashSet<>(first.lines().filter(l -> l.startsWith("  ")).map(hit).toList());
        List<String> againHits = again.lines().filter(l -> l.startsWith("  ")).map(hit).toList();
        assertTrue(againHits.size() == 10, "the limit is filled with further hits: " + again);
        assertTrue(againHits.stream().noneMatch(firstHits::contains), "no hit is given twice: " + again);
    }

    @Test
    void anUnrelatedQuestionStartsANewThread() {
        ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        String other = ask("data_map", "name", "stock");
        assertTrue(footer(other).contains("thread t2 new"), footer(other));
    }

    @Test
    void anEditedFileIsGivenInFullAgain() throws Exception {
        ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        Path impl = repo.resolve("order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java");
        Files.writeString(impl, Files.readString(impl).replace("No order ", "Unknown order "));
        String after = ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        assertTrue(after.contains("OrderServiceImpl#cancelOrder  order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java:45\n"),
                "the changed file is cited in full and expanded: " + after);
        assertTrue(after.contains("  OrderController#cancelOrder  OrderController.java:37\n"),
                "a node above the change is expanded again, its unchanged file cited by name: " + after);
        assertTrue(after.contains("OrderEventPublisher#publishCancelled  OrderEventPublisher.java:21  (expanded earlier in this thread)"),
                "an unchanged subtree stays collapsed: " + after);
    }

    @Test
    void afterTheIdleWindowAQuestionStartsFresh() {
        ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        now += dev.agentideate.session.ThreadStore.CONTINUE_WINDOW_MS + 1;
        String later = ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        assertTrue(footer(later).contains("thread t2 new"), footer(later));
    }

    @Test
    void recallListsThreadsAndRestoresOneForANewChat() {
        ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        ask("impact_of", "target", "OrderServiceImpl#cancelOrder");
        ask("data_map", "name", "stock");
        String list = ask("thread_recall");
        assertTrue(list.contains("t2") && list.contains("t1") && list.indexOf("t2") < list.indexOf("t1 "), list);

        String recalled = ask("thread_recall", "topic", "cancel");
        assertTrue(recalled.startsWith("Thread t1: endpoint_flow /api/orders/{id}/cancel  (2 questions"), recalled);
        assertTrue(recalled.contains("  2. impact_of OrderServiceImpl#cancelOrder"), recalled);
        assertTrue(recalled.contains("order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java  (2)"), recalled);

        // the recalling chat has not seen the earlier answers: nothing may be left out now
        String next = ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        assertTrue(footer(next).contains("thread t1 continued"), footer(next));
        assertFalse(next.contains("expanded earlier in this thread"), next);
        assertTrue(next.contains("OrderServiceImpl#cancelOrder  OrderServiceImpl.java:45"),
                "files the recall printed are known by name: " + next);
    }

    @Test
    void recallOfAnUnknownTopicSaysSo() {
        assertTrue(ask("thread_recall").startsWith("No conversation threads yet"));
        ask("endpoint_flow", "path", "/api/orders/{id}/cancel");
        assertTrue(ask("thread_recall", "topic", "payroll").startsWith("Not found: no thread about \"payroll\""));
    }
}
