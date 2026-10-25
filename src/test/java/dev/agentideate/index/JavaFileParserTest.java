package dev.agentideate.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFileParserTest {

    private final JavaFileParser parser = new JavaFileParser();

    private TypeDecl only(String src) {
        JavaFileParser.Parsed p = parser.parse(src);
        assertNull(p.error(), p.error());
        return p.types().get(0);
    }

    @Test
    void readsPackageImportsAndNestedTypes() {
        JavaFileParser.Parsed p = parser.parse("""
                package a.b;
                import java.util.List;
                import static a.b.C.X;
                import a.d.*;
                class Outer { static class Inner {} }
                """);
        assertEquals("a.b", p.packageName());
        assertEquals(List.of("java.util.List", "static a.b.C.X", "a.d.*"), p.imports());
        assertEquals(List.of("a.b.Outer", "a.b.Outer.Inner"), p.types().stream().map(TypeDecl::fqn).toList());
    }

    @Test
    void endpointsCombineMappingKindsAndKeepConstantReferences() {
        TypeDecl t = only("""
                @RestController @RequestMapping(Paths.ORDERS)
                class C {
                  @GetMapping("/{id}") Object get() { return null; }
                  @PostMapping Object create() { return null; }
                  @RequestMapping(value = "/x", method = RequestMethod.PATCH) void patch() {}
                  @RequestMapping(path = {"/a", "/b"}) void both() {}
                }
                """);
        assertEquals("${Paths.ORDERS}", t.basePath());
        List<String> eps = t.endpoints().stream().map(e -> e.httpMethod() + " " + e.path() + " " + e.handler()).toList();
        assertEquals(List.of("GET /{id} get", "POST  create", "PATCH /x patch", "ANY /a both", "ANY /b both"), eps);
        assertTrue(t.endpoints().stream().noneMatch(Endpoint::client));
    }

    @Test
    void feignMappingsAreMarkedAsClientCalls() {
        TypeDecl t = only("""
                @FeignClient(name = "pricing", path = "/v1")
                interface P { @GetMapping("/prices/{sku}") Object price(String sku); }
                """);
        assertEquals("/v1", t.basePath());
        assertTrue(t.endpoints().get(0).client());
    }

    @Test
    void injectionThroughConstructorFieldSetterAndLombok() {
        TypeDecl ctor = only("""
                @Service class S { private final Repo repo; S(Repo repo, Other o) { this.repo = repo; } }
                """);
        assertEquals(List.of("repo:Repo:constructor", "o:Other:constructor"), inj(ctor));

        TypeDecl field = only("""
                @Component class S { @Autowired private Repo repo; private String plain;
                  @Autowired void setClock(Clock clock) {} }
                """);
        assertEquals(List.of("clock:Clock:setter", "repo:Repo:field"), inj(field));

        TypeDecl lombok = only("""
                @Service @RequiredArgsConstructor class S {
                  private final Repo repo; private final List<String> names; private int counter;
                  private static final String X = "x"; private final Clock clock = null; }
                """);
        assertEquals(List.of("repo:Repo:lombok", "names:List:lombok"), inj(lombok));
    }

    @Test
    void constructorParametersOfAPlainClassAreNotInjections() {
        TypeDecl t = only("class Money { Money(java.math.BigDecimal amount) {} }");
        assertTrue(t.injections().isEmpty());
    }

    private static List<String> inj(TypeDecl t) {
        return t.injections().stream().map(i -> i.name() + ":" + i.type() + ":" + i.via()).toList();
    }

    @Test
    void callsResolveReceiverFromParameterLocalFieldVarAndStatic() {
        TypeDecl t = only("""
                class S {
                  private Repo repo;
                  void run(Client client) {
                    Helper h = new Helper();
                    var made = new Maker();
                    repo.save(1);
                    this.repo.flush();
                    client.send();
                    h.help();
                    made.make();
                    Util.format("x");
                    local();
                    repo.find().get();
                  }
                  void local() {}
                }
                """);
        MethodDecl run = t.methods().stream().filter(m -> m.name().equals("run")).findFirst().orElseThrow();
        List<String> calls = run.calls().stream().map(c -> c.scopeType() + "." + c.method()).toList();
        assertTrue(calls.containsAll(List.of("Repo.save", "Repo.flush", "Client.send", "Helper.help", "Maker.make",
                "Util.format", "null.local", "Repo.find")), calls.toString());
        assertTrue(calls.stream().noneMatch(c -> c.endsWith(".get")), "a chained call is not attributed: " + calls);
    }

    @Test
    void aLocalShadowsAFieldOfTheSameName() {
        TypeDecl t = only("""
                class S { private Repo repo; void run() { Other repo = new Other(); repo.go(); } }
                """);
        assertEquals("Other", t.methods().get(0).calls().get(0).scopeType());
    }

    @Test
    void callArgumentsAreRenderedSoChannelNamesCanBeRead() {
        TypeDecl t = only("""
                class P {
                  @Value("${app.topic}") private String configured;
                  private KafkaTemplate<String, Object> kafka;
                  void run(String topic, Object payload) {
                    kafka.send("literal", payload);
                    kafka.send(Topics.ORDERS, payload);
                    kafka.send(LOCAL_CONST, payload);
                    kafka.send(configured, payload);
                    kafka.send(this.configured, payload);
                    kafka.send(topic, payload);
                    kafka.send("pre." + Topics.SUFFIX, payload);
                    kafka.send(build(), payload);
                  }
                }
                """);
        MethodDecl run = t.methods().stream().filter(m -> m.name().equals("run")).findFirst().orElseThrow();
        List<String> first = run.calls().stream().filter(c -> c.method().equals("send")).map(c -> c.args().get(0)).toList();
        assertEquals(List.of("literal", "${Topics.ORDERS}", "${LOCAL_CONST}", "${app.topic}", "${app.topic}", "<topic>",
                "pre.${Topics.SUFFIX}", "?"), first);
        assertEquals(2, run.calls().get(0).args().size());
    }

    @Test
    void genericSupertypeArgumentsAreKept() {
        TypeDecl t = only("interface OrderRepository extends JpaRepository<OrderEntity, Long>, Custom {}");
        assertEquals(List.of("OrderEntity", "Long"), t.superTypeArgs().get("JpaRepository"));
        assertNull(t.superTypeArgs().get("Custom"));
    }

    @Test
    void persistenceContextIsAnInjection() {
        TypeDecl t = only("@Repository class R { @PersistenceContext private EntityManager em; }");
        assertEquals(List.of("em:EntityManager:field"), inj(t));
    }

    @Test
    void constantsKeepLiteralsAndConcatenations() {
        TypeDecl t = only("""
                class Paths { static final String API = "/api"; static final String ORDERS = API + "/orders";
                  final String notStatic = "x"; static String notFinal = "y"; }
                """);
        assertEquals("/api", t.constants().get("API"));
        assertEquals("${API}/orders", t.constants().get("ORDERS"));
        assertEquals(2, t.constants().size());
    }

    @Test
    void signaturesAndLineRanges() {
        TypeDecl t = only("""
                class S {
                  java.util.List<String> names(int max, String... filters) {
                    return null;
                  }
                }
                """);
        MethodDecl m = t.methods().get(0);
        assertEquals("java.util.List<String> names(int max, String... filters)", m.signature());
        assertEquals(2, m.line());
        assertEquals(4, m.endLine());
    }

    @Test
    void modernSyntaxParses() {
        TypeDecl t = only("""
                record R(int a) { R { if (a < 0) throw new IllegalArgumentException(); } }
                """);
        assertEquals("record", t.kind());
        assertNotNull(parser.parse("sealed interface S permits A {} final class A implements S {}").types());
        assertNull(parser.parse("""
                class X { String f(Object o) { return switch (o) { case Integer i -> "i"; default -> "d"; }; } }
                """).error());
    }

    @Test
    void brokenSourceReportsAnErrorInsteadOfThrowing() {
        JavaFileParser.Parsed p = parser.parse("class { broken");
        assertNotNull(p.error());
        assertTrue(p.types().isEmpty());
    }
}
