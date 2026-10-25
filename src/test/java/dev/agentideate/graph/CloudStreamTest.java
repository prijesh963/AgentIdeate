package dev.agentideate.graph;

import dev.agentideate.graph.Integrations.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudStreamTest {

    private static final String KAFKA_BUILD = "dependencies { implementation 'org.springframework.cloud:spring-cloud-stream-binder-kafka' }";

    @TempDir
    Path tmp;

    private CodeGraph repo(String... pathThenContent) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < pathThenContent.length; i += 2) {
            files.put(pathThenContent[i], pathThenContent[i + 1]);
        }
        Path r = tmp.resolve("svc");
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = r.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
        }
        return CodeGraphTest.graphOf(tmp.resolve("state"), r);
    }

    private static List<String> msgs(CodeGraph g) {
        return g.integrations().messages().stream().map(m -> m.system() + " " + m.channel() + " " + m.role() + " "
                + m.owner().name() + "#" + m.method().name() + (m.group() == null ? "" : " group=" + m.group())).sorted().toList();
    }

    private static final String FUNCTIONS = """
            @Configuration class F {
              @Bean Function<String, String> upper() { return s -> s; }
              @Bean Consumer<String> sink() { return s -> {}; }
            }""";

    @Test
    void aSingleFunctionIsBoundWithoutADefinitionAndDefaultsDestinationToTheBindingName() throws Exception {
        CodeGraph g = repo("build.gradle", KAFKA_BUILD,
                "src/main/java/F.java", "@Configuration class F { @Bean Function<String, String> upper() { return s -> s; } }");
        assertEquals(List.of("kafka upper-in-0 consumer F#upper", "kafka upper-out-0 producer F#upper"), msgs(g));
    }

    @Test
    void severalFunctionsWithoutADefinitionAreReportedUnbound() throws Exception {
        CodeGraph g = repo("build.gradle", KAFKA_BUILD, "src/main/java/F.java", FUNCTIONS);
        List<Message> m = g.integrations().messages();
        assertEquals(2, m.size());
        assertTrue(m.stream().allMatch(x -> !x.resolved() && x.detail().contains("several functions and no spring.cloud.function.definition")), m.toString());
    }

    @Test
    void theDefinitionSelectsFunctionsAndConfigNamesTheTopicsAndGroups() throws Exception {
        CodeGraph g = repo("build.gradle", KAFKA_BUILD, "src/main/java/F.java", FUNCTIONS,
                "src/main/resources/application.yml", """
                        spring.cloud.function.definition: sink
                        spring:
                          cloud:
                            stream:
                              bindings:
                                sink-in-0:
                                  destination: ${app.topic}
                                  group: g1
                        app:
                          topic: events
                        """);
        List<String> got = msgs(g);
        assertTrue(got.contains("kafka events consumer F#sink group=g1"), got.toString());
        Message upper = g.integrations().messages().stream().filter(x -> x.method().name().equals("upper")).findFirst().orElseThrow();
        assertTrue(upper.detail().contains("not in spring.cloud.function.definition"), upper.detail());
    }

    @Test
    void compositionConcatenatesBindingNamesAndSplitsInputAndOutput() throws Exception {
        CodeGraph g = repo("build.gradle", KAFKA_BUILD, "src/main/java/F.java", """
                @Configuration class F {
                  @Bean Function<String, String> parse() { return s -> s; }
                  @Bean Function<String, String> enrich() { return s -> s; }
                }""", "src/main/resources/application.properties", """
                spring.cloud.function.definition=parse|enrich
                spring.cloud.stream.bindings.parseenrich-in-0.destination=raw
                spring.cloud.stream.bindings.parseenrich-out-0.destination=enriched
                """);
        assertEquals(List.of("kafka enriched producer F#enrich", "kafka raw consumer F#parse"), msgs(g));
    }

    @Test
    void bindingRenamesAreFollowed() throws Exception {
        CodeGraph g = repo("build.gradle", KAFKA_BUILD,
                "src/main/java/F.java", "@Configuration class F { @Bean Consumer<String> sink() { return s -> {}; } }",
                "src/main/resources/application.properties", """
                        spring.cloud.stream.function.bindings.sink-in-0=input
                        spring.cloud.stream.bindings.input.destination=orders
                        """);
        assertEquals(List.of("kafka orders consumer F#sink"), msgs(g));
    }

    @Test
    void streamBridgeUsesABindingsDestinationOrTheNameItself() throws Exception {
        CodeGraph g = repo("build.gradle", KAFKA_BUILD, "src/main/java/S.java", """
                @Service class S { private final StreamBridge bridge; S(StreamBridge bridge) { this.bridge = bridge; }
                  void a(Object o) { bridge.send("out-binding", o); }
                  void b(Object o) { bridge.send("direct.topic", o); } }""",
                "src/main/resources/application.properties", "spring.cloud.stream.bindings.out-binding.destination=mapped\n");
        assertEquals(List.of("kafka direct.topic producer S#b", "kafka mapped producer S#a"), msgs(g));
    }

    @Test
    void theBinderComesFromConfigOrDependenciesOrIsLeftAsStream() throws Exception {
        CodeGraph rabbit = repo("pom.xml", "<artifactId>spring-cloud-starter-stream-rabbit</artifactId>",
                "src/main/java/F.java", "@Configuration class F { @Bean Consumer<String> sink() { return s -> {}; } }");
        assertEquals(List.of("rabbitmq sink-in-0 consumer F#sink"), msgs(rabbit));

        Path other = tmp.resolve("other");
        Files.createDirectories(other.resolve("src/main/resources"));
        Files.createDirectories(other.resolve("src/main/java"));
        Files.writeString(other.resolve("build.gradle"), "implementation 'org.springframework.cloud:spring-cloud-stream'");
        Files.writeString(other.resolve("src/main/java/F.java"), "@Configuration class F { @Bean Consumer<String> sink() { return s -> {}; } }");
        CodeGraph unknown = CodeGraphTest.graphOf(tmp.resolve("state2"), other);
        assertEquals(List.of("stream sink-in-0 consumer F#sink"), msgs(unknown));

        Files.writeString(other.resolve("src/main/resources/application.yml"), """
                spring:
                  cloud:
                    stream:
                      default-binder: main
                      binders:
                        main:
                          type: rabbit
                """);
        assertEquals(List.of("rabbitmq sink-in-0 consumer F#sink"), msgs(CodeGraphTest.graphOf(tmp.resolve("state3"), other)));
    }

    @Test
    void aComponentImplementingConsumerIsAFunctionalBean() throws Exception {
        CodeGraph g = repo("build.gradle", KAFKA_BUILD, "src/main/java/AuditSink.java", """
                @Component class AuditSink implements Consumer<String> { public void accept(String s) {} }""");
        assertEquals(List.of("kafka auditSink-in-0 consumer AuditSink#accept"), msgs(g));
    }

    @Test
    void functionBeansOutsideAStreamModuleAreIgnored() throws Exception {
        CodeGraph g = repo("build.gradle", "implementation 'org.springframework.boot:spring-boot-starter-web'",
                "src/main/java/F.java", "@Configuration class F { @Bean Function<String, String> upper() { return s -> s; } }");
        assertTrue(g.integrations().messages().isEmpty());
    }

    @Test
    void functionalKinds() {
        assertEquals("function", CloudStream.functionalKind("java.util.function.Function<Flux<A>, Flux<B>>"));
        assertEquals("consumer", CloudStream.functionalKind("Consumer<Message<Order>>"));
        assertEquals("supplier", CloudStream.functionalKind("Supplier<String>"));
        assertNull(CloudStream.functionalKind("Callable<String>"));
    }
}
