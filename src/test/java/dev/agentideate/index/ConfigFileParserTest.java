package dev.agentideate.index;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileParserTest {

    @Test
    void nestedMapsFlattenToDottedKeys() {
        Map<String, String> p = ConfigFileParser.yaml("""
                spring:
                  application:
                    name: order-service   # trailing comment
                  datasource:
                    url: "jdbc:postgresql://db:5432/orders"
                    password: 'se#cret'
                server:
                  port: 8080
                """);
        assertEquals("order-service", p.get("spring.application.name"));
        assertEquals("jdbc:postgresql://db:5432/orders", p.get("spring.datasource.url"));
        assertEquals("se#cret", p.get("spring.datasource.password"), "a # inside quotes is not a comment");
        assertEquals("8080", p.get("server.port"));
    }

    @Test
    void listsInBothIndentStylesAndFlowStyle() {
        Map<String, String> p = ConfigFileParser.yaml("""
                a:
                  topics:
                  - one
                  - two
                b:
                  topics:
                    - three
                c: [x, "y"]
                """);
        assertEquals("one,two", p.get("a.topics"));
        assertEquals("one", p.get("a.topics[0]"));
        assertEquals("three", p.get("b.topics"));
        assertEquals("x,y", p.get("c"));
    }

    @Test
    void listOfMaps() {
        Map<String, String> p = ConfigFileParser.yaml("""
                routes:
                  - id: orders
                    uri: lb://order-service
                    predicates:
                      path: /api/orders/**
                  - id: prices
                    uri: lb://pricing-service
                after: 1
                """);
        assertEquals("orders", p.get("routes[0].id"));
        assertEquals("lb://order-service", p.get("routes[0].uri"));
        assertEquals("/api/orders/**", p.get("routes[0].predicates.path"));
        assertEquals("prices", p.get("routes[1].id"));
        assertEquals("lb://pricing-service", p.get("routes[1].uri"));
        assertEquals("1", p.get("after"));
    }

    @Test
    void blockScalarsAndDocumentsFirstValueWins() {
        Map<String, String> p = ConfigFileParser.yaml("""
                note: |
                  line one
                  line two
                key: base
                ---
                spring:
                  config:
                    activate:
                      on-profile: dev
                key: dev
                """);
        assertEquals("line one\nline two", p.get("note"));
        assertEquals("base", p.get("key"));
        assertEquals("dev", p.get("spring.config.activate.on-profile"));
    }

    @Test
    void urlsWithColonsAreValuesNotKeys() {
        Map<String, String> p = ConfigFileParser.yaml("url: http://host:8080/x\n");
        assertEquals("http://host:8080/x", p.get("url"));
        assertEquals(-1, ConfigFileParser.keyColon("http://host:8080"));
    }

    @Test
    void secretsAreRedactedBeforeTheyAreStored() {
        Map<String, String> p = ConfigFileParser.properties("application.yml", """
                spring:
                  datasource:
                    url: jdbc:postgresql://app:hunter2@db:5432/orders
                    password: hunter2
                  kafka:
                    properties:
                      sasl.jaas.config: x
                app:
                  api-key: abc
                  stripe:
                    secret-key: sk_live_x
                  token-ttl: 30
                """);
        assertEquals("jdbc:postgresql://***@db:5432/orders", p.get("spring.datasource.url"));
        assertEquals(ConfigFileParser.REDACTED, p.get("spring.datasource.password"));
        assertEquals(ConfigFileParser.REDACTED, p.get("app.api-key"));
        assertEquals(ConfigFileParser.REDACTED, p.get("app.stripe.secret-key"));
        assertEquals(ConfigFileParser.REDACTED, p.get("app.token-ttl"), "over-redacting a harmless key is the safe side");
        assertTrue(p.values().stream().noneMatch(v -> v.contains("hunter2")));
    }

    @Test
    void javaProperties() {
        Map<String, String> p = ConfigFileParser.properties("application.properties",
                "spring.application.name=pricing\n# comment\nlist=a,\\\n  b\n");
        assertEquals("pricing", p.get("spring.application.name"));
        assertEquals("a,b", p.get("list"));
    }

    @Test
    void whichFilesAreRead() {
        assertTrue(ConfigFileParser.isSpringConfig("svc/src/main/resources/application-dev.yml"));
        assertTrue(ConfigFileParser.isSpringConfig("bootstrap.properties"));
        assertFalse(ConfigFileParser.isSpringConfig("svc/src/main/resources/logback.xml"));
        assertTrue(ConfigFileParser.isMigration("db/migration/V1__init.sql"));
        assertTrue(ConfigFileParser.isMigration("db/changelog/db.changelog-master.xml"));
        assertFalse(ConfigFileParser.isMigration("pom.xml"));
    }

    @Test
    void sqlTables() {
        assertEquals(List.of("orders", "order_lines", "audit"), ConfigFileParser.tables("V1.sql", """
                create table orders (id bigint);
                CREATE TABLE IF NOT EXISTS public."order_lines" (id bigint);
                alter table Orders add column x int;
                ALTER TABLE `audit` ADD y int;
                insert into ignored values (1);
                """));
    }

    @Test
    void liquibaseTables() {
        assertEquals(List.of("invoice", "invoice_line"), ConfigFileParser.tables("db.changelog.xml", """
                <changeSet id="1"><createTable tableName="invoice"><column name="id"/></createTable></changeSet>
                <changeSet id="2"><addColumn tableName="invoice_line"><column name="x"/></addColumn></changeSet>
                """));
    }

    @Test
    void brokenInputDoesNotThrow() {
        assertNull(ConfigFileParser.yaml("::: [\n  - : -\n").get("x"));
    }
}
