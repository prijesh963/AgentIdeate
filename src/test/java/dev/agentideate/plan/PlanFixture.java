package dev.agentideate.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import dev.agentideate.workspace.WorkspaceService;

import java.nio.file.Path;
import java.util.List;

/** A writable copy of the sample and helpers to build plan requests. */
final class PlanFixture {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String IMPL = "order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java";
    static final String TOTAL_LINE = "        order.setTotal(total(price.amount(), request.quantity()));";
    static final String DISCOUNTED = "        order.setTotal(total(price.amount(), request.quantity()).multiply(DISCOUNT));";

    final Path repo;
    final WorkspaceService workspaces;

    PlanFixture(Path tmp) throws Exception {
        repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        workspaces = new WorkspaceService(new AppHome(tmp.resolve("home")), List.of(repo), null);
    }

    WorkspaceService.Snapshot snapshot() throws Exception {
        return workspaces.snapshot(null);
    }

    PlanService service() throws Exception {
        return new PlanService(snapshot(), 1_000L);
    }

    static ObjectNode edit(String file, String before, String after) {
        ObjectNode c = JSON.createObjectNode();
        c.put("kind", "edit").put("file", file).put("before", before).put("after", after).put("reason", "test");
        return c;
    }

    static ObjectNode plan(ObjectNode... changes) {
        ObjectNode p = JSON.createObjectNode();
        p.put("title", "Discount").put("story", "10% off").put("understanding", "Apply a discount to order totals.");
        var arr = p.putArray("changes");
        for (ObjectNode c : changes) {
            arr.add(c);
        }
        return p;
    }
}
