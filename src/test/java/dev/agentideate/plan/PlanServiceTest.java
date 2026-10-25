package dev.agentideate.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.agentideate.plan.PlanFixture.DISCOUNTED;
import static dev.agentideate.plan.PlanFixture.IMPL;
import static dev.agentideate.plan.PlanFixture.JSON;
import static dev.agentideate.plan.PlanFixture.TOTAL_LINE;
import static dev.agentideate.plan.PlanFixture.edit;
import static dev.agentideate.plan.PlanFixture.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanServiceTest {

    @TempDir
    Path tmp;

    PlanFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new PlanFixture(tmp);
    }

    private Plan first() throws Exception {
        return fx.service().submit(plan(edit(IMPL, TOTAL_LINE, DISCOUNTED),
                edit(IMPL, "    private final PricingClient pricingClient;", "    private final PricingClient pricingClient;\n    private final java.math.BigDecimal discount = java.math.BigDecimal.ONE;")));
    }

    private ObjectNode revise(String plan) {
        return JSON.createObjectNode().put("plan", plan);
    }

    @Test
    void aNewPlanGetsIdsAndIsStored() throws Exception {
        Plan p = first();
        assertEquals("p1", p.id);
        assertEquals(List.of("c1", "c2"), p.latest().changes.stream().map(c -> c.id).toList());
        assertEquals("p1", fx.service().store().get("p1").id);
        assertEquals("p2", fx.service().submit(plan(edit(IMPL, TOTAL_LINE, DISCOUNTED))).id);
        assertThrows(PlanService.PlanException.class, () -> fx.service().submit(JSON.createObjectNode().put("understanding", "x")),
                "a new plan needs a title");
    }

    @Test
    void aRevisionIsAPatchAndUntouchedChangesCarryOver() throws Exception {
        first();
        ObjectNode r = revise("p1");
        ObjectNode replacement = edit(IMPL, TOTAL_LINE, "        order.setTotal(total(price.amount(), request.quantity()).subtract(java.math.BigDecimal.ONE));");
        replacement.put("id", "c1");
        r.putArray("changes").add(replacement).add(edit("pricing-service/src/main/java/com/shop/pricing/service/PriceService.java",
                "    public Price currentPrice(String sku) {", "    public Price currentPrice(String sku) { // reviewed"));
        r.putArray("remove").add("c2");
        Plan p = fx.service().submit(r);
        PlanVersion v2 = p.latest();
        assertEquals(2, v2.number);
        assertEquals(List.of("c1", "c3"), v2.changes.stream().map(c -> c.id).toList());
        assertEquals("changed", v2.changeStatus.get("c1"));
        assertEquals("new", v2.changeStatus.get("c3"));
        assertEquals(1, v2.removed.size());
        assertTrue(v2.removed.get(0).startsWith("c2 edit"));
        assertEquals("Apply a discount to order totals.", v2.understanding, "the understanding carries over too");

        Plan p3 = fx.service().submit(revise("p1").put("understanding", "Same, phrased better."));
        assertEquals("same", p3.latest().changeStatus.get("c1"), "a revision that sends no changes keeps them all");
        assertEquals(List.of("c1", "c3"), p3.latest().changes.stream().map(c -> c.id).toList());
    }

    @Test
    void badRevisionsAreRefusedWithAReason() throws Exception {
        first();
        ObjectNode unknown = revise("p1");
        unknown.putArray("changes").add(edit(IMPL, TOTAL_LINE, DISCOUNTED).put("id", "c9"));
        assertTrue(assertThrows(PlanService.PlanException.class, () -> fx.service().submit(unknown)).getMessage().contains("no change 'c9'"));
        ObjectNode remove = revise("p1");
        remove.putArray("remove").add("c7");
        assertThrows(PlanService.PlanException.class, () -> fx.service().submit(remove));
        assertThrows(PlanService.PlanException.class, () -> fx.service().submit(revise("p42")));
    }

    @Test
    void decisionsAccumulateAreEnforcedAndCanBeDropped() throws Exception {
        first();
        ObjectNode r = revise("p1").put("feedback", "keep the controller as it is");
        ObjectNode d = r.putArray("decisions_add").addObject();
        d.put("text", "Do not change the controller");
        d.putArray("forbid").add("**/web/*Controller.java");
        r.putArray("changes").add(edit("order-service/src/main/java/com/shop/order/web/OrderController.java",
                "    @PostMapping\n", "    @PostMapping(\"\")\n"));
        Plan p = fx.service().submit(r);
        assertEquals("d1", p.decisions.get(0).id);
        assertEquals("keep the controller as it is", p.latest().feedback);
        assertTrue(p.latest().errors.stream().anyMatch(e -> e.contains("which decision d1 rules out: Do not change the controller")),
                p.latest().errors.toString());

        ObjectNode drop = revise("p1");
        drop.putArray("decisions_drop").add("d1");
        assertTrue(fx.service().submit(drop).latest().errors.isEmpty(), "with the decision dropped the change is fine");
    }

    @Test
    void requestedChangesBecomeTheNextVersionsFeedback() throws Exception {
        Plan p = first();
        PlanService s = fx.service();
        s.requestChanges(p, "use a 5% discount");
        assertEquals("use a 5% discount", s.store().get("p1").pendingFeedback);
        Plan v2 = fx.service().submit(revise("p1"));
        assertEquals("use a 5% discount", v2.latest().feedback);
        assertNull(v2.pendingFeedback);
    }

    @Test
    void onlyTheLatestValidVersionIsApprovableAndARevisionClearsApproval() throws Exception {
        Plan p = first();
        PlanService s = fx.service();
        assertNull(s.approvalBlocker(p, p.latest()));
        s.approve(p, p.latest(), "test");
        Plan stored = s.store().get("p1");
        assertTrue(stored.approved());
        assertEquals(stored.latest().contentHash, stored.approvedHash);

        Plan v2 = fx.service().submit(revise("p1").put("understanding", "changed"));
        assertFalse(v2.approved(), "a new version needs its own approval");
        assertNull(v2.approvedHash);
        assertTrue(fx.service().approvalBlocker(v2, v2.version(1)).contains("not the latest version"));
    }

    @Test
    void aFileChangedSinceThePlanBlocksApproval() throws Exception {
        Plan p = first();
        Path impl = fx.repo.resolve(IMPL);
        Files.writeString(impl, Files.readString(impl).replace(TOTAL_LINE, "        order.setTotal(price.amount());"));
        PlanService s = fx.service();
        String blocker = s.approvalBlocker(p, p.latest());
        assertNotNull(blocker);
        assertTrue(blocker.contains("problem") && blocker.contains("c1: the quoted text is not in"), blocker);
    }

    @Test
    void contentHashFollowsTheContent() throws Exception {
        Plan p = first();
        String h = p.latest().contentHash;
        Plan same = fx.service().submit(revise("p1"));
        assertEquals(h, same.latest().contentHash);
        Plan changed = fx.service().submit(revise("p1").put("understanding", "different"));
        assertFalse(h.equals(changed.latest().contentHash));
    }
}
