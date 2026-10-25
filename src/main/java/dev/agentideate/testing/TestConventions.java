package dev.agentideate.testing;

import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.Ann;
import dev.agentideate.index.FieldDecl;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.index.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a module's tests are written, read from its existing tests (imports, annotations, names) and,
 * when it has none, from its build file's test dependencies. New tests follow these, so they look
 * like the team's own and need no new dependency.
 *
 * @param style   what the tests use, e.g. {@code JUnit 5}, {@code Mockito (@ExtendWith(MockitoExtension.class))}
 * @param source  where that was read from
 * @param suffix  the usual test class suffix ({@code Test}, {@code Tests} or {@code IT})
 * @param example the shortest existing {@code @Test} method, to copy the style from, or {@code null}
 */
public record TestConventions(List<String> style, String source, String suffix, TypeNode exampleType, MethodDecl example) {

    static final int MAX_EXAMPLE_LINES = 25;
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    private static final List<String> SLICES = List.of("SpringBootTest", "WebMvcTest", "WebFluxTest", "DataJpaTest", "DataMongoTest",
            "JdbcTest", "JsonTest", "RestClientTest");

    /** The conventions of the module {@code module} in root {@code root}. */
    public static TestConventions detect(CodeGraph graph, String root, String module) {
        List<TypeNode> tests = new ArrayList<>();
        for (TypeNode t : graph.types()) {
            SourceFile f = t.file();
            if (f.root().equals(root) && f.module().equals(module) && f.isTest() && isTestClass(t)) {
                tests.add(t);
            }
        }
        Set<String> style = new LinkedHashSet<>();
        Map<String, Integer> suffixes = new LinkedHashMap<>();
        TypeNode exampleType = null;
        MethodDecl example = null;
        for (TypeNode t : tests) {
            Set<String> imports = new LinkedHashSet<>();
            t.file().imports().forEach(i -> imports.add(i.startsWith("static ") ? i.substring(7) : i));
            if (any(imports, "org.junit.jupiter.")) {
                style.add("JUnit 5");
            } else if (any(imports, "org.junit.")) {
                style.add("JUnit 4");
            }
            Ann ext = t.decl().annotation("ExtendWith");
            Ann runWith = t.decl().annotation("RunWith");
            if (ext != null && String.valueOf(ext.attr("value")).contains("MockitoExtension")) {
                style.add("Mockito (@ExtendWith(MockitoExtension.class), @Mock/@InjectMocks)");
            } else if (runWith != null && String.valueOf(runWith.attr("value")).contains("Mockito")) {
                style.add("Mockito (@RunWith(MockitoJUnitRunner.class))");
            } else if (any(imports, "org.mockito.")) {
                style.add("Mockito");
            }
            if (any(imports, "org.assertj.")) {
                style.add("AssertJ assertThat");
            }
            if (any(imports, "org.hamcrest.")) {
                style.add("Hamcrest");
            }
            for (String slice : SLICES) {
                if (t.decl().hasAnnotation(slice)) {
                    style.add("@" + slice + " (" + t.name() + ")");
                }
            }
            if (t.decl().fields().stream().anyMatch(f -> hasAnn(f, "MockBean") || hasAnn(f, "MockitoBean"))) {
                style.add("@MockBean in Spring tests");
            }
            if (any(imports, "org.testcontainers.")) {
                style.add("Testcontainers");
            }
            if (t.decl().hasAnnotation("EmbeddedKafka")) {
                style.add("@EmbeddedKafka");
            }
            for (String s : List.of("Tests", "Test", "IT")) {
                if (t.name().endsWith(s)) {
                    suffixes.merge(s, 1, Integer::sum);
                    break;
                }
            }
            for (MethodDecl m : t.decl().methods()) {
                boolean isTest = m.annotations().stream().anyMatch(a -> TEST_ANNOTATIONS.contains(a.name()));
                int length = m.endLine() - m.line();
                if (isTest && length < MAX_EXAMPLE_LINES && (example == null || length < example.endLine() - example.line())) {
                    example = m;
                    exampleType = t;
                }
            }
        }
        String suffix = suffixes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("Test");
        if (!tests.isEmpty()) {
            return new TestConventions(List.copyOf(style), tests.size() + " existing test class" + (tests.size() == 1 ? "" : "es"),
                    suffix, exampleType, example);
        }
        // No tests yet: what the build makes available.
        for (SourceFile f : graph.files()) {
            if (f.root().equals(root) && f.module().equals(module) && !f.dependencies().isEmpty()) {
                List<String> deps = f.dependencies();
                if (deps.contains("spring-boot-starter-test")) {
                    style.add("JUnit 5");
                    style.add("Mockito");
                    style.add("AssertJ assertThat");
                }
                if (deps.contains("junit-jupiter") || deps.contains("junit-jupiter-api")) {
                    style.add("JUnit 5");
                }
                if (deps.contains("junit")) {
                    style.add("JUnit 4");
                }
                if (deps.stream().anyMatch(d -> d.startsWith("mockito"))) {
                    style.add("Mockito");
                }
                if (deps.contains("assertj-core")) {
                    style.add("AssertJ assertThat");
                }
                if (deps.stream().anyMatch(d -> d.equals("testcontainers") || d.equals("junit-jupiter") && deps.contains("postgresql"))) {
                    style.add("Testcontainers");
                }
                if (deps.contains("spring-kafka-test")) {
                    style.add("spring-kafka-test (@EmbeddedKafka)");
                }
                return new TestConventions(List.copyOf(style), "no tests yet; the test dependencies of " + f.path(), "Test", null, null);
            }
        }
        return new TestConventions(List.of(), "no tests and no build file found", "Test", null, null);
    }

    private static boolean isTestClass(TypeNode t) {
        return t.decl().methods().stream().anyMatch(m -> m.annotations().stream().anyMatch(a -> TEST_ANNOTATIONS.contains(a.name())))
                || t.name().endsWith("Test") || t.name().endsWith("Tests") || t.name().endsWith("IT");
    }

    private static boolean hasAnn(FieldDecl f, String name) {
        return f.annotations().stream().anyMatch(a -> a.name().equals(name));
    }

    private static boolean any(Set<String> imports, String prefix) {
        return imports.stream().anyMatch(i -> i.startsWith(prefix));
    }
}
