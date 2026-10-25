package dev.agentideate.graph;

import dev.agentideate.TestSupport;
import dev.agentideate.graph.Integrations.Message;
import dev.agentideate.graph.Integrations.Store;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationsTest {

    static CodeGraph shop;

    @BeforeAll
    static void build(@TempDir Path state) throws Exception {
        shop = CodeGraphTest.graphOf(state, TestSupport.shop());
    }

    private static List<String> describe(List<Message> msgs) {
        return msgs.stream().map(m -> m.system() + " " + m.channel() + " " + m.role() + " " + m.owner().name()
                + "#" + m.method().name() + (m.group() == null ? "" : " group=" + m.group())).sorted().toList();
    }

    @Test
    void kafkaProducersAndConsumersAcrossServices() {
        assertEquals(List.of(
                "kafka order.cancelled consumer OrderCancelledListener#onOrderCancelled group=inventory",
                "kafka order.cancelled producer OrderEventPublisher#publishCancelled",
                "kafka order.created consumer OrderCreatedListener#onOrderCreated group=inventory",
                "kafka order.created producer OrderEventPublisher#publishCreated"), describe(shop.integrations().messages()));
    }

    @Test
    void aProducerMatchesItsConsumerInAnotherService() {
        Integrations integ = shop.integrations();
        Message created = integ.messages().stream()
                .filter(m -> m.producer() && m.channel().equals("order.created")).findFirst().orElseThrow();
        List<Message> consumers = integ.counterparts(created);
        assertEquals(1, consumers.size());
        assertEquals("inventory-service", shop.moduleKey(consumers.get(0).owner().file()));
    }

    @Test
    void entitiesTablesCollectionsAndRepositories() {
        Integrations integ = shop.integrations();
        assertEquals(List.of("jpa orders OrderEntity explicit", "jpa prices Price explicit", "mongodb stock StockItem explicit"),
                integ.entities().stream().map(e -> e.store() + " " + e.name() + " " + e.type().name()
                        + (e.explicitName() ? " explicit" : " default")).sorted().toList());
        assertEquals(List.of("OrderRepository -> OrderEntity (jpa)", "PriceRepository -> Price (jpa)", "StockRepository -> StockItem (mongodb)"),
                integ.repos().stream().map(r -> r.repo().name() + " -> " + r.entity().name() + " (" + r.store() + ")").sorted().toList());
    }

    @Test
    void datastoresAndBrokersFromConfigNeverEchoingCredentials() {
        Map<String, List<Store>> stores = shop.integrations().stores();
        assertEquals(List.of(new Store("postgresql", "orders", "datastore"), new Store("kafka", "", "broker")), stores.get("order-service"));
        assertEquals(List.of(new Store("mongodb", "inventory", "datastore"), new Store("kafka", "", "broker")), stores.get("inventory-service"));
        assertEquals(List.of(new Store("postgresql", "pricing", "datastore")), stores.get("pricing-service"));
    }

    @Test
    void migrationsForATableAreSortedAndDirectTemplateUseIsFound() {
        Integrations integ = shop.integrations();
        var order = integ.entities().stream().filter(e -> e.name().equals("orders")).findFirst().orElseThrow();
        assertEquals(List.of("order-service/src/main/resources/db/migration/V1__create_orders.sql",
                        "order-service/src/main/resources/db/migration/V2__add_order_status.sql"),
                integ.migrationsFor("orders", order.type()).stream().map(f -> f.path()).toList());
        assertEquals(List.of("PriceHistoryDao JdbcTemplate"),
                integ.directAccess().stream().map(d -> d.owner().name() + " " + d.via()).toList());
    }

    @Test
    void applicationNameAndPlaceholderResolution() {
        Integrations integ = shop.integrations();
        assertEquals("inventory-service", integ.applicationName("inventory-service"));
        var listener = CodeGraphTest.type(shop, "OrderCancelledListener");
        assertEquals("order.cancelled", integ.resolveValue("${app.topics.order-cancelled}", listener));
        assertEquals("fallback", integ.resolveValue("${app.missing:fallback}", listener));
        assertEquals("${app.missing}", integ.resolveValue("${app.missing}", listener), "unresolved stays visibly unresolved");
    }

    private static CodeGraph repo(Path tmp, Map<String, String> files) throws Exception {
        Path r = tmp.resolve("r");
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = r.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
        }
        return CodeGraphTest.graphOf(tmp.resolve("state"), r);
    }

    @Test
    void rabbitAndJmsProducersMatchTheirListeners(@TempDir Path tmp) throws Exception {
        CodeGraph g = repo(tmp, Map.of(
                "a/Sender.java", """
                        @Service class Sender {
                          private final RabbitTemplate rabbit; private final JmsTemplate jms;
                          Sender(RabbitTemplate rabbit, JmsTemplate jms) { this.rabbit = rabbit; this.jms = jms; }
                          void go(Object o) { rabbit.convertAndSend("orders-x", "invoice.created", o); jms.convertAndSend("INVOICES.Q", o); }
                        }""",
                "b/Receiver.java", """
                        @Component class Receiver {
                          @RabbitListener(queues = "invoice.created") void onRabbit(Object o) {}
                          @JmsListener(destination = "INVOICES.Q") void onJms(Object o) {}
                        }"""));
        Integrations integ = g.integrations();
        for (Message p : integ.messages().stream().filter(Message::producer).toList()) {
            assertEquals(1, integ.counterparts(p).size(), p.toString());
        }
        Message rabbit = integ.messages().stream().filter(m -> m.producer() && m.system().equals("rabbitmq")).findFirst().orElseThrow();
        assertEquals("exchange orders-x", rabbit.detail());
    }

    @Test
    void aRuntimeTopicIsUnresolvedAndMatchesNothing(@TempDir Path tmp) throws Exception {
        CodeGraph g = repo(tmp, Map.of(
                "P.java", """
                        @Service class P { private final KafkaTemplate<String, Object> k; P(KafkaTemplate<String, Object> k) { this.k = k; }
                          void send(String topic, Object o) { k.send(topic, o); } }""",
                "C.java", "@Component class C { @KafkaListener(topics = \"anything\") void on(Object o) {} }"));
        Message p = g.integrations().messages().stream().filter(Message::producer).findFirst().orElseThrow();
        assertEquals("<topic>", p.channel());
        assertFalse(p.resolved());
        assertTrue(g.integrations().counterparts(p).isEmpty());
    }

    @Test
    void topicPatternSubscriptionsMatchByRegex(@TempDir Path tmp) throws Exception {
        CodeGraph g = repo(tmp, Map.of(
                "P.java", """
                        @Service class P { private final KafkaTemplate<String, Object> k; P(KafkaTemplate<String, Object> k) { this.k = k; }
                          void send(Object o) { k.send("audit.orders", o); } }""",
                "C.java", "@Component class C { @KafkaListener(topicPattern = \"audit\\\\..*\") void on(Object o) {} }"));
        Message p = g.integrations().messages().stream().filter(Message::producer).findFirst().orElseThrow();
        assertEquals(1, g.integrations().counterparts(p).size());
    }

    @Test
    void classLevelKafkaListenerWithHandlers(@TempDir Path tmp) throws Exception {
        CodeGraph g = repo(tmp, Map.of("C.java", """
                @Component @KafkaListener(topics = "multi") class C {
                  @KafkaHandler void a(String s) {} @KafkaHandler void b(Integer i) {} void notAHandler() {} }"""));
        assertEquals(List.of("kafka multi consumer C#a", "kafka multi consumer C#b"), describe(g.integrations().messages()));
    }

    @Test
    void defaultTableAndCollectionNames(@TempDir Path tmp) throws Exception {
        CodeGraph g = repo(tmp, Map.of(
                "OrderLine.java", "@Entity class OrderLine { @Id Long id; }",
                "AuditEvent.java", "@Document class AuditEvent { }"));
        assertEquals(List.of("jpa order_line default", "mongodb auditEvent default"),
                g.integrations().entities().stream().map(e -> e.store() + " " + e.name() + (e.explicitName() ? "" : " default")).sorted().toList());
    }

    @Test
    void jdbcUrlsKeepOnlyKindAndDatabase() {
        assertEquals(new Store("postgresql", "orders", "datastore"), Integrations.jdbcStore("jdbc:postgresql://user:pw@db:5432/orders?ssl=true"));
        assertEquals(new Store("sqlserver", "billing", "datastore"), Integrations.jdbcStore("jdbc:sqlserver://h:1433;databaseName=billing;encrypt=true"));
        assertEquals(new Store("oracle", "ORCLPDB", "datastore"), Integrations.jdbcStore("jdbc:oracle:thin:@//h:1521/ORCLPDB"));
        assertEquals(new Store("mysql", "shop", "datastore"), Integrations.jdbcStore("jdbc:mysql://h/shop"));
        assertEquals(new Store("sql", "", "datastore"), Integrations.jdbcStore("${DB_URL}"));
    }

    @Test
    void namingHelpers() {
        assertEquals("order_line", Integrations.snakeCase("OrderLine"));
        assertEquals("html_page", Integrations.snakeCase("HTMLPage"));
        assertEquals("stockItem", Integrations.decapitalize("StockItem"));
        assertNull(Integrations.storesFrom(Map.of()).stream().findFirst().orElse(null));
    }
}
