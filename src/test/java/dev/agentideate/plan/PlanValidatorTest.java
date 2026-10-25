package dev.agentideate.plan;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.agentideate.plan.PlanFixture.DISCOUNTED;
import static dev.agentideate.plan.PlanFixture.IMPL;
import static dev.agentideate.plan.PlanFixture.TOTAL_LINE;
import static dev.agentideate.plan.PlanFixture.edit;
import static dev.agentideate.plan.PlanFixture.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanValidatorTest {

    @TempDir
    Path tmp;

    PlanFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new PlanFixture(tmp);
    }

    private PlanVersion submit(ObjectNode request) throws Exception {
        return fx.service().submit(request).latest();
    }

    private static ObjectNode add(String file, String content) {
        ObjectNode c = PlanFixture.JSON.createObjectNode();
        c.put("kind", "add").put("file", file).put("after", content);
        return c;
    }

    private static ObjectNode delete(String file) {
        ObjectNode c = PlanFixture.JSON.createObjectNode();
        c.put("kind", "delete").put("file", file);
        return c;
    }

    private static boolean has(PlanVersion v, String text) {
        return v.errors.stream().anyMatch(e -> e.contains(text));
    }

    @Test
    void anExactQuoteIsLocatedOnItsLines() throws Exception {
        PlanVersion v = submit(plan(edit(IMPL, TOTAL_LINE, DISCOUNTED)));
        assertTrue(v.errors.isEmpty(), v.errors.toString());
        assertEquals(38, v.changes.get(0).startLine);
        assertEquals(38, v.changes.get(0).endLine);
        assertEquals("order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java", v.changes.get(0).path);
        assertTrue(v.fileHashes.containsKey("shop:" + IMPL));
    }

    @Test
    void aQuoteThatDoesNotMatchExactlyOnceIsAProblemThatSaysWhy() throws Exception {
        PlanVersion none = submit(plan(edit(IMPL, "order.setTotal(total(price.amount(),request.quantity()));", "x();")));
        assertTrue(has(none, "c1: the quoted text is not in"), none.errors.toString());
        assertTrue(has(none, "similar text with different spacing is at line 38"), none.errors.toString());

        PlanVersion many = submit(plan(edit(IMPL, "orderRepository.save(order)", "x")));
        assertTrue(has(many, "matches 2 places"), many.errors.toString());
        assertTrue(has(many, "(lines 40, 52)"), many.errors.toString());
    }

    @Test
    void anEditThatBreaksTheSyntaxIsRefused() throws Exception {
        PlanVersion v = submit(plan(edit(IMPL, TOTAL_LINE, "        order.setTotal(total(price.amount(), request.quantity())")));
        assertTrue(has(v, "no longer parses"), v.errors.toString());
    }

    @Test
    void multiLineEditsSeveralEditsPerFileAndOverlap() throws Exception {
        String twoLines = "        order.setSku(request.sku());\n        order.setQuantity(request.quantity());";
        PlanVersion ok = submit(plan(edit(IMPL, twoLines, "        order.setSku(request.sku().trim());\n        order.setQuantity(request.quantity());"),
                edit(IMPL, TOTAL_LINE, DISCOUNTED)));
        assertTrue(ok.errors.isEmpty(), ok.errors.toString());
        assertEquals(36, ok.changes.get(0).startLine);
        assertEquals(37, ok.changes.get(0).endLine);

        PlanVersion overlap = submit(plan(edit(IMPL, twoLines, "x();"), edit(IMPL, "        order.setQuantity(request.quantity());\n" + TOTAL_LINE, "y();")));
        assertTrue(has(overlap, "overlaps c1"), overlap.errors.toString());
    }

    @Test
    void aWindowsFileIsMatchedOnItsContent() throws Exception {
        Path impl = fx.repo.resolve(IMPL);
        Files.writeString(impl, Files.readString(impl).replace("\n", "\r\n"));
        PlanVersion v = submit(plan(edit(IMPL, TOTAL_LINE, DISCOUNTED)));
        assertTrue(v.errors.isEmpty(), v.errors.toString());
        assertEquals(38, v.changes.get(0).startLine);
    }

    @Test
    void newFilesMustBeNewParseAndSitInTheirPackage() throws Exception {
        String dir = "order-service/src/main/java/com/shop/order/service/";
        PlanVersion exists = submit(plan(add(IMPL, "class X {}")));
        assertTrue(has(exists, "already exists; to change it use kind edit"), exists.errors.toString());

        PlanVersion wrongPkg = submit(plan(add(dir + "DiscountPolicy.java", "package com.shop.billing;\npublic class DiscountPolicy {}\n")));
        assertTrue(has(wrongPkg, "package com.shop.billing does not match the folder"), wrongPkg.errors.toString());

        PlanVersion wrongName = submit(plan(add(dir + "DiscountPolicy.java", "package com.shop.order.service;\npublic class Discounts {}\n")));
        assertTrue(has(wrongName, "public type Discounts must be in Discounts.java"), wrongName.errors.toString());

        PlanVersion broken = submit(plan(add(dir + "DiscountPolicy.java", "package com.shop.order.service;\npublic class DiscountPolicy {")));
        assertTrue(has(broken, "does not parse"), broken.errors.toString());

        PlanVersion good = submit(plan(add(dir + "DiscountPolicy.java", "package com.shop.order.service;\n\npublic class DiscountPolicy {\n}\n")));
        assertTrue(good.errors.isEmpty(), good.errors.toString());
        assertEquals(4, good.changes.get(0).endLine);
    }

    @Test
    void deletingAReferencedFileIsAProblemUnlessTheReferenceIsInThePlan() throws Exception {
        String publisher = "order-service/src/main/java/com/shop/order/messaging/OrderEventPublisher.java";
        PlanVersion v = submit(plan(delete(publisher)));
        assertTrue(has(v, "Deleting OrderEventPublisher leaves it referenced by " + IMPL), v.errors.toString());

        PlanVersion both = submit(plan(delete(publisher), edit(IMPL, "    private final OrderEventPublisher eventPublisher;\n", "")));
        assertTrue(both.errors.stream().noneMatch(e -> e.contains("Deleting OrderEventPublisher")), both.errors.toString());
        assertTrue(both.warnings.stream().anyMatch(w -> w.contains("refers to OrderEventPublisher, which the plan deletes")), both.warnings.toString());
    }

    @Test
    void evidenceMustBeInTheIndex() throws Exception {
        ObjectNode c = edit(IMPL, TOTAL_LINE, DISCOUNTED);
        c.putArray("evidence").add("OrderServiceImpl#placeOrder").add("OrderRepository#save").add("order.created").add("orders")
                .add("PaymentGateway").add("OrderServiceImpl#refund");
        PlanVersion v = submit(plan(c));
        assertEquals(2, v.errors.size(), v.errors.toString());
        assertTrue(has(v, "evidence 'PaymentGateway' is not in the index"), v.errors.toString());
        assertTrue(has(v, "evidence 'OrderServiceImpl#refund' is not in the index"), v.errors.toString());
    }

    @Test
    void filesOutsideTheProjectNeverResolve() throws Exception {
        PlanVersion up = submit(plan(add("../outside.txt", "x")));
        assertTrue(has(up, "'../outside.txt' is not a file inside the project folders"), up.errors.toString());
        PlanVersion abs = submit(plan(add(tmp.resolve("elsewhere/X.java").toString(), "class X {}")));
        assertTrue(has(abs, "is not a file inside the project folders"), abs.errors.toString());
        PlanVersion missing = submit(plan(edit("order-service/Nope.java", "a", "b"), delete("order-service/Gone.java")));
        assertTrue(has(missing, "does not exist; to create it use kind add"), missing.errors.toString());
        assertTrue(has(missing, "does not exist, so it cannot be deleted"), missing.errors.toString());
    }

    @Test
    void changesThatReachOtherServicesAreFlagged() throws Exception {
        PlanVersion v = submit(plan(edit("order-service/src/main/java/com/shop/order/messaging/OrderEventPublisher.java",
                "    public void publishCreated(OrderEntity order) {", "    public void publishCreated(OrderEntity order) { // note")));
        assertTrue(v.errors.isEmpty(), v.errors.toString());
        assertTrue(v.warnings.stream().anyMatch(w -> w.contains("sends kafka order.created to inventory-service (OrderCreatedListener#onOrderCreated), "
                + "shipping-service (ShippingFunctions#shipOrder), not in this plan")), v.warnings.toString());

        PlanVersion pricing = submit(plan(edit("pricing-service/src/main/java/com/shop/pricing/service/PriceService.java",
                "    public Price currentPrice(String sku) {", "    public Price currentPrice(String sku) { // note")));
        assertTrue(pricing.warnings.isEmpty(), "a change used only inside its own service raises nothing: " + pricing.warnings);
    }

    @Test
    void aPlanNeedsAnUnderstandingAndChanges() throws Exception {
        ObjectNode p = plan();
        p.remove("understanding");
        PlanVersion v = submit(p);
        assertTrue(has(v, "needs an understanding"), v.errors.toString());
        assertTrue(has(v, "has no changes"), v.errors.toString());
    }
}
