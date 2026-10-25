package dev.agentideate.graph;

import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.Ann;
import dev.agentideate.index.CallSite;
import dev.agentideate.index.InjectionPoint;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.index.SourceFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a workspace talks to: messaging (Kafka, RabbitMQ, JMS), datastores (JPA entities and
 * tables, Mongo documents and collections, Spring Data repositories, direct template access), and
 * the Spring config that names them. Built once per graph, on first use.
 *
 * <p>Values are resolved the way Spring would see them where that can be done statically: a Java
 * constant first, then a {@code ${property}} placeholder from the module's own config files
 * (with its {@code :default}). Whatever stays unresolved is reported as unresolved, never guessed.
 */
public final class Integrations {

    public record Message(String system, String channel, String role, TypeNode owner, MethodDecl method,
                          int line, String group, String detail) {

        /** A channel name known statically (not a runtime variable or an unresolved placeholder). */
        public boolean resolved() {
            return !channel.contains("${") && !channel.contains("<") && !channel.contains("?") && !channel.contains("#{");
        }

        public boolean producer() {
            return role.equals("producer");
        }
    }

    /** A persistent type: a JPA/JDBC table, a Mongo collection, a Redis hash. */
    public record Entity(TypeNode type, String store, String name, boolean explicitName) {
    }

    public record Repo(TypeNode repo, TypeNode entity, String entityName, String store) {
    }

    /** A datastore or broker a module is configured for. {@code name} is a database name, or {@code ""}. */
    public record Store(String kind, String name, String role) {
    }

    public record DirectAccess(TypeNode owner, String via, int line) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private static final Set<String> KAFKA_TEMPLATES = Set.of("KafkaTemplate", "ReactiveKafkaProducerTemplate", "KafkaOperations");
    private static final Set<String> RABBIT_TEMPLATES = Set.of("RabbitTemplate", "AmqpTemplate", "RabbitOperations", "RabbitMessagingTemplate");
    private static final Set<String> JMS_TEMPLATES = Set.of("JmsTemplate", "JmsMessagingTemplate", "JmsOperations");
    private static final Set<String> SEND_METHODS = Set.of("send", "sendDefault", "convertAndSend", "convertSendAndReceive", "sendAndReceive");

    static final Set<String> DIRECT_ACCESS_TYPES = Set.of(
            "JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcClient", "MongoTemplate", "ReactiveMongoTemplate",
            "MongoOperations", "EntityManager", "RedisTemplate", "StringRedisTemplate", "DSLContext",
            "R2dbcEntityTemplate", "DatabaseClient");

    private final CodeGraph graph;
    private final Map<String, Map<String, String>> propertiesByModule = new LinkedHashMap<>();
    private final List<Message> messages = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Map<TypeNode, Entity> entityByType = new IdentityHashMap<>();
    private final List<Repo> repos = new ArrayList<>();
    private final Map<TypeNode, Repo> repoByType = new IdentityHashMap<>();
    private final Map<String, List<Store>> stores = new TreeMap<>();
    private final List<DirectAccess> directAccess = new ArrayList<>();
    private final Map<String, List<String>> dependenciesByModule = new LinkedHashMap<>();
    private final CloudStream stream;

    Integrations(CodeGraph graph) {
        this.graph = graph;
        collectProperties();
        collectDependencies();
        this.stream = new CloudStream(graph, this);
        for (TypeNode n : graph.types()) {
            if (n.file().isTest()) {
                continue;
            }
            collectMessaging(n);
            collectEntity(n);
            for (InjectionPoint ip : n.decl().injections()) {
                if (DIRECT_ACCESS_TYPES.contains(ip.type())) {
                    directAccess.add(new DirectAccess(n, ip.type(), ip.line()));
                }
            }
        }
        for (TypeNode n : graph.types()) {
            if ("spring-data-repository".equals(graph.beanKind(n)) && !n.file().isTest()) {
                collectRepo(n);
            }
        }
        stream.collect(messages);
        propertiesByModule.forEach((module, props) -> stores.put(module, storesFrom(props)));
    }

    // ---- config ---------------------------------------------------------------------------

    private void collectProperties() {
        List<SourceFile> configs = new ArrayList<>();
        for (SourceFile f : graph.files()) {
            if (!f.properties().isEmpty() && !f.isTest()) {
                configs.add(f);
            }
        }
        // application.yml before application-dev.yml: the base file wins for a key both define,
        // because no profile is known to be active.
        configs.sort(Comparator.comparing((SourceFile f) -> fileName(f).contains("-") ? 1 : 0)
                .thenComparing(SourceFile::path));
        for (SourceFile f : configs) {
            Map<String, String> props = propertiesByModule.computeIfAbsent(graph.moduleKey(f), k -> new LinkedHashMap<>());
            f.properties().forEach(props::putIfAbsent);
        }
    }

    private static String fileName(SourceFile f) {
        return f.path().substring(f.path().lastIndexOf('/') + 1);
    }

    private void collectDependencies() {
        Map<String, List<String>> rootLevel = new LinkedHashMap<>();
        for (SourceFile f : graph.files()) {
            if (!f.dependencies().isEmpty()) {
                dependenciesByModule.computeIfAbsent(graph.moduleKey(f), k -> new ArrayList<>()).addAll(f.dependencies());
                if (f.module().isEmpty()) {
                    rootLevel.computeIfAbsent(f.root(), k -> new ArrayList<>()).addAll(f.dependencies());
                }
            }
        }
        // a multi-module build often declares shared dependencies in the root build file
        for (SourceFile f : graph.files()) {
            String module = graph.moduleKey(f);
            if (!f.module().isEmpty() && rootLevel.containsKey(f.root())) {
                List<String> deps = dependenciesByModule.computeIfAbsent(module, k -> new ArrayList<>());
                for (String d : rootLevel.get(f.root())) {
                    if (!deps.contains(d)) {
                        deps.add(d);
                    }
                }
            }
        }
    }

    /** Artifact ids declared by a module's build file, plus its root build file's. */
    public List<String> dependencies(String moduleKey) {
        return dependenciesByModule.getOrDefault(moduleKey, List.of());
    }

    public Map<String, String> properties(String moduleKey) {
        return propertiesByModule.getOrDefault(moduleKey, Map.of());
    }

    /** A module's {@code spring.application.name}, or {@code null}. */
    public String applicationName(String moduleKey) {
        return properties(moduleKey).get("spring.application.name");
    }

    /** Resolves Java constants, then {@code ${property:default}} placeholders from the context's module config. */
    public String resolveValue(String raw, TypeNode context) {
        if (raw == null) {
            return null;
        }
        String v = graph.resolveConstants(raw, context);
        Map<String, String> props = context == null ? Map.of() : properties(graph.moduleKey(context.file()));
        for (int depth = 0; depth < 5 && v.contains("${"); depth++) {
            Matcher m = PLACEHOLDER.matcher(v);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                String value = props.get(m.group(1).trim());
                if (value == null) {
                    value = m.group(2); // the placeholder's own default, when it has one
                }
                if (value != null) {
                    any = true;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
            }
            m.appendTail(sb);
            v = sb.toString();
            if (!any) {
                break;
            }
        }
        return v;
    }

    // ---- messaging ------------------------------------------------------------------------

    private void collectMessaging(TypeNode n) {
        String module = graph.moduleKey(n.file());
        Ann classListener = n.decl().annotation("KafkaListener");
        for (MethodDecl m : n.decl().methods()) {
            for (CallSite c : m.calls()) {
                producer(n, m, c);
            }
            for (Ann a : m.annotations()) {
                switch (a.name()) {
                    case "KafkaListener" -> consumers(n, m, "kafka", a, kafkaChannels(a), kafkaGroup(a, module));
                    case "RabbitListener" -> consumers(n, m, "rabbitmq", a, a.firstAttr("queues", "value"), null);
                    case "JmsListener" -> consumers(n, m, "jms", a, a.attr("destination"), null);
                    case "KafkaHandler" -> {
                        if (classListener != null) {
                            consumers(n, m, "kafka", classListener, kafkaChannels(classListener), kafkaGroup(classListener, module));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static String kafkaChannels(Ann a) {
        String topics = a.firstAttr("topics", "value");
        if (topics != null) {
            return topics;
        }
        String pattern = a.attr("topicPattern");
        return pattern == null ? null : "pattern:" + pattern;
    }

    private String kafkaGroup(Ann a, String module) {
        String g = a.firstAttr("groupId", "id");
        if (g == null) {
            g = properties(module).get("spring.kafka.consumer.group-id");
        }
        return g;
    }

    private void consumers(TypeNode n, MethodDecl m, String system, Ann a, String rawChannels, String group) {
        if (rawChannels == null) {
            messages.add(new Message(system, "?", "consumer", n, m, m.line(), group, "channel not in the annotation"));
            return;
        }
        for (String raw : rawChannels.split(",")) {
            String channel = resolveValue(raw.trim(), n);
            for (String c : channel.split(",")) { // a placeholder may resolve to a comma list
                messages.add(new Message(system, c.trim(), "consumer", n, m, m.line(),
                        group == null ? null : resolveValue(group, n), null));
            }
        }
    }

    private void producer(TypeNode n, MethodDecl m, CallSite c) {
        if (c.scopeType() == null || !SEND_METHODS.contains(c.method())) {
            return;
        }
        List<String> args = c.args() == null ? List.of() : c.args();
        String module = graph.moduleKey(n.file());
        if (KAFKA_TEMPLATES.contains(c.scopeType())) {
            String raw = c.method().equals("sendDefault")
                    ? properties(module).getOrDefault("spring.kafka.template.default-topic", "?")
                    : args.size() >= 2 ? args.get(0) : "?";
            messages.add(new Message("kafka", resolveValue(raw, n), "producer", n, m, c.line(), null,
                    args.size() == 1 ? "sends a ProducerRecord/Message; topic not read" : null));
        } else if (RABBIT_TEMPLATES.contains(c.scopeType())) {
            String raw;
            String detail = null;
            if (args.size() >= 3) {
                raw = args.get(1);
                detail = "exchange " + resolveValue(args.get(0), n);
            } else if (args.size() == 2) {
                raw = args.get(0);
                detail = "default exchange";
            } else {
                raw = "?";
            }
            messages.add(new Message("rabbitmq", resolveValue(raw, n), "producer", n, m, c.line(), null, detail));
        } else if (c.scopeType().equals("StreamBridge") && c.method().equals("send")) {
            messages.add(stream.streamBridge(module, args.size() >= 2 ? args.get(0) : "?", n, m, c.line()));
        } else if (JMS_TEMPLATES.contains(c.scopeType())) {
            String raw = args.size() >= 2 ? args.get(0)
                    : properties(module).getOrDefault("spring.jms.template.default-destination", "?");
            messages.add(new Message("jms", resolveValue(raw, n), "producer", n, m, c.line(), null, null));
        }
    }

    public List<Message> messages() {
        return messages;
    }

    /** Messages produced or consumed by one method. */
    public List<Message> messagesIn(TypeNode owner, MethodDecl method) {
        List<Message> out = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.owner() == owner && msg.method() == method) {
                out.add(msg);
            }
        }
        return out;
    }

    /**
     * The other side of a message: consumers of a producer's channel or producers of a consumer's.
     * Matched on system and exact channel name; a {@code pattern:} subscription matches by regex.
     * An unresolved channel matches nothing rather than everything.
     */
    public List<Message> counterparts(Message msg) {
        List<Message> out = new ArrayList<>();
        if (!msg.resolved()) {
            return out;
        }
        for (Message other : messages) {
            if (other.producer() == msg.producer() || !other.system().equals(msg.system()) || !other.resolved()) {
                continue;
            }
            Message producer = msg.producer() ? msg : other;
            Message consumer = msg.producer() ? other : msg;
            if (channelMatches(consumer.channel(), producer.channel())) {
                out.add(other);
            }
        }
        return out;
    }

    static boolean channelMatches(String consumerChannel, String producerChannel) {
        if (consumerChannel.startsWith("pattern:")) {
            try {
                return producerChannel.matches(consumerChannel.substring("pattern:".length()));
            } catch (RuntimeException e) {
                return false;
            }
        }
        return consumerChannel.equals(producerChannel);
    }

    // ---- data -----------------------------------------------------------------------------

    private void collectEntity(TypeNode n) {
        var d = n.decl();
        Entity e = null;
        if (d.hasAnnotation("Entity")) {
            Ann table = d.annotation("Table");
            String name = table == null ? null : table.firstAttr("name", "value");
            e = new Entity(n, "jpa", name != null ? resolveValue(name, n).toLowerCase(Locale.ROOT) : snakeCase(n.name()), name != null);
        } else if (d.hasAnnotation("Document")) {
            String name = d.annotation("Document").firstAttr("collection", "value");
            e = new Entity(n, "mongodb", name != null ? resolveValue(name, n) : decapitalize(n.name()), name != null);
        } else if (d.hasAnnotation("Table")) { // Spring Data JDBC / R2DBC
            String name = d.annotation("Table").firstAttr("name", "value");
            e = new Entity(n, "jdbc", name != null ? resolveValue(name, n).toLowerCase(Locale.ROOT) : snakeCase(n.name()), name != null);
        } else if (d.hasAnnotation("RedisHash")) {
            String name = d.annotation("RedisHash").firstAttr("value");
            e = new Entity(n, "redis", name != null ? resolveValue(name, n) : n.fqn(), name != null);
        }
        if (e != null) {
            entities.add(e);
            entityByType.put(n, e);
        }
    }

    private void collectRepo(TypeNode repo) {
        for (Map.Entry<String, List<String>> st : repo.decl().superTypeArgs().entrySet()) {
            if (st.getValue().isEmpty()) {
                continue;
            }
            String base = st.getKey();
            boolean dataBase = CodeGraph.SPRING_DATA_BASES.contains(base);
            TypeNode parent = graph.resolve(base, repo);
            if (!dataBase && (parent == null || !"spring-data-repository".equals(graph.beanKind(parent)))) {
                continue;
            }
            String entityName = st.getValue().get(0);
            TypeNode entity = graph.resolve(entityName, repo);
            String store;
            if (base.contains("Mongo")) {
                store = "mongodb";
            } else if (base.startsWith("Jpa")) {
                store = "jpa";
            } else if (entity != null && entityByType.containsKey(entity)) {
                store = entityByType.get(entity).store();
            } else {
                store = "spring-data";
            }
            Repo r = new Repo(repo, entity, entityName, store);
            repos.add(r);
            repoByType.put(repo, r);
            return;
        }
        Repo r = new Repo(repo, null, "?", "spring-data");
        repos.add(r);
        repoByType.put(repo, r);
    }

    public List<Entity> entities() {
        return entities;
    }

    public Entity entityOf(TypeNode n) {
        return entityByType.get(n);
    }

    public List<Repo> repos() {
        return repos;
    }

    public Repo repoOf(TypeNode n) {
        return repoByType.get(n);
    }

    public List<Repo> reposFor(TypeNode entity) {
        List<Repo> out = new ArrayList<>();
        for (Repo r : repos) {
            if (r.entity() == entity) {
                out.add(r);
            }
        }
        return out;
    }

    /** Migration files that create or alter {@code table}, in the same root. */
    public List<SourceFile> migrationsFor(String table, TypeNode near) {
        List<SourceFile> out = new ArrayList<>();
        for (SourceFile f : graph.files()) {
            if (f.tables().contains(table) && (near == null || f.root().equals(near.file().root()))) {
                out.add(f);
            }
        }
        out.sort(Comparator.comparing(SourceFile::path));
        return out;
    }

    /** Configured datastores and brokers per module ({@code moduleKey} order). */
    public Map<String, List<Store>> stores() {
        return stores;
    }

    public List<DirectAccess> directAccess() {
        return directAccess;
    }

    static List<Store> storesFrom(Map<String, String> p) {
        List<Store> out = new ArrayList<>();
        String jdbc = first(p, "spring.datasource.url", "spring.datasource.jdbc-url", "spring.r2dbc.url");
        if (jdbc != null) {
            out.add(jdbcStore(jdbc));
        }
        String mongoUri = p.get("spring.data.mongodb.uri");
        String mongoDb = p.get("spring.data.mongodb.database");
        if (mongoUri != null || mongoDb != null) {
            String db = mongoDb != null ? mongoDb : lastPathSegment(mongoUri);
            out.add(new Store("mongodb", unresolvedToBlank(db), "datastore"));
        }
        if (first(p, "spring.data.redis.host", "spring.redis.host", "spring.data.redis.url", "spring.redis.url") != null) {
            out.add(new Store("redis", "", "datastore"));
        }
        if (p.containsKey("spring.kafka.bootstrap-servers")
                || p.keySet().stream().anyMatch(k -> k.startsWith("spring.cloud.stream.kafka.binder."))) {
            out.add(new Store("kafka", "", "broker"));
        }
        if (first(p, "spring.rabbitmq.host", "spring.rabbitmq.addresses") != null) {
            out.add(new Store("rabbitmq", "", "broker"));
        }
        if (first(p, "spring.activemq.broker-url", "spring.artemis.broker-url") != null) {
            out.add(new Store("activemq", "", "broker"));
        }
        if (p.keySet().stream().anyMatch(k -> k.startsWith("ibm.mq."))) {
            out.add(new Store("ibm-mq", "", "broker"));
        }
        return out;
    }

    /**
     * The kind and database name of a JDBC/R2DBC URL. Only those two are kept: hosts, users and
     * passwords that a URL may carry are never echoed into an answer.
     */
    static Store jdbcStore(String url) {
        if (url.contains("${")) {
            return new Store("sql", "", "datastore"); // the URL comes from the environment
        }
        String[] parts = url.split(":");
        String kind = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "sql";
        String db;
        int dn = url.indexOf("databaseName=");
        if (dn >= 0) {
            db = url.substring(dn + "databaseName=".length()).split("[;&]")[0];
        } else {
            db = lastPathSegment(url);
            if (db.isEmpty() && kind.equals("oracle")) {
                db = url.substring(url.lastIndexOf(':') + 1);
            }
        }
        return new Store(kind, db, "datastore");
    }

    private static String lastPathSegment(String uri) {
        if (uri == null || uri.contains("${")) {
            return "";
        }
        String s = uri.split("[?;]")[0];
        int scheme = s.indexOf("//");
        String afterHost = scheme >= 0 ? s.substring(scheme + 2) : s;
        int slash = afterHost.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String path = afterHost.substring(slash + 1);
        return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    }

    private static String unresolvedToBlank(String s) {
        return s == null || s.contains("${") ? "" : s;
    }

    private static String first(Map<String, String> p, String... keys) {
        for (String k : keys) {
            if (p.get(k) != null && !p.get(k).isBlank()) {
                return p.get(k);
            }
        }
        return null;
    }

    /** Spring Boot's default physical naming: {@code OrderLine -> order_line}. */
    static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** Spring Data MongoDB's default collection name: the simple class name, first letter lower-cased. */
    static String decapitalize(String name) {
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
