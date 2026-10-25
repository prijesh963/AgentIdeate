# Known limitations

What agentideate does not do yet, or does approximately. Recorded here so it is not rediscovered
the hard way. Each answer also states its own blind spots (for example the "Not shown:" line of
`endpoint_flow` and the "Not searched:" line of a `locate` miss).

## Indexing

1. **Syntax only, no compiler.** Types are resolved by imports, package and unique names, not by
   compiling. A name that is ambiguous after those rules (two classes `Dup` in different packages,
   neither imported) resolves to nothing rather than a guess, so a call through it is not shown.
2. **Chained calls are not followed.** `a.b().c()` records `a.b()` only; resolving `.c()` needs the
   return type of `b()`, which is not computed yet.
3. **Overloads collapse to the first method with the name.** A call to `save(Order)` and
   `save(List<Order>)` both point at whichever `save` is declared first.
4. **Inherited methods are not searched up the class hierarchy.** An unqualified call to a method
   defined in a superclass is not shown.
5. **Calls inside lambdas are attributed to the enclosing method,** which is usually what is wanted
   but loses the lambda's own scope (a lambda parameter shadowing a field is not modelled).
6. **Only Spring config, migrations and build-file dependencies are read among non-Java files.**
   `application*.yml|yaml|properties` and `bootstrap*` become flat keys; `.sql` files and Liquibase
   XML changelogs give the tables they create or alter; `build.gradle(.kts)` and `pom.xml` give the
   artifact ids they name (quoted `group:artifact` coordinates, `<artifactId>`). Version catalogs,
   plugins and other XML are not interpreted.
7. **The YAML reader is a subset.** Nested maps, scalars, quotes, comments, lists (both indent
   styles), lists of maps, flow lists and block scalars. Anchors/aliases (`&x`, `*x`), merge keys and
   flow maps (`{a: 1}`) are not interpreted. With several `---` documents (profiles) the first value
   of a key wins; profile-specific files (`application-dev.yml`) never override the base file,
   because no active profile is known.
8. **Config comes from the repo only.** Values set through environment variables, a config server,
   Vault or Kubernetes ConfigMaps are invisible, so a placeholder they fill stays unresolved and is
   shown as such.
9. **Files over 2 MB are listed but not parsed** (generated sources, mostly). They are counted as
   unparseable in the coverage line.
10. **Constants:** a route path or attribute given as a `static final String` constant is resolved,
   including concatenation (`API + "/orders"`) and static imports. A value computed at runtime, or a
   Spring property placeholder that no config file in the module defines, is kept as written; an
   unresolved constant shows as `${NAME}` instead of being read as literal path text.

## Spring semantics

1. **Bean detection is annotation-based.** `@Component`/`@Service`/`@Repository`/`@Controller`/
   `@RestController`/`@Configuration`, Spring Data repository interfaces, Feign clients. `@Bean`
   factory methods and beans registered through `@Import` or XML are not modelled yet.
2. **Interface dispatch lists every implementation.** `@Primary`, `@Qualifier` and profile
   conditions are not used to pick one; a call on an interface with two implementations shows both.
3. **Only the first class-level `@RequestMapping` path is used** when several are given.
4. **Feign hops are matched by HTTP method and path template only,** against routes in a different
   module. The Feign client's `name` is shown but not yet used to choose the target service when two
   services expose the same path.

## Messaging

1. **Recognized producers:** `send`/`sendDefault`/`convertAndSend`/`convertSendAndReceive`/
   `sendAndReceive` on `KafkaTemplate`, `ReactiveKafkaProducerTemplate`, `KafkaOperations`,
   `RabbitTemplate`, `AmqpTemplate`, `RabbitOperations`, `RabbitMessagingTemplate`, `JmsTemplate`,
   `JmsMessagingTemplate`, `JmsOperations`, called on a field, parameter or local of that type.
   Not recognized: the plain Kafka client (`KafkaProducer`), Camel routes, Spring Integration flows,
   and calls through a wrapper class the index cannot see into.
2. **Recognized consumers:** `@KafkaListener` (`topics`, `topicPattern`; class-level with
   `@KafkaHandler`), `@RabbitListener(queues)`, `@JmsListener(destination)`. Not recognized:
   `@RabbitListener(bindings = @QueueBinding(...))`, programmatic listener containers.
3. **Spring Cloud Stream, functional style, is recognized:** `@Bean` methods returning
   `Function`/`Consumer`/`Supplier` (and `@Component` classes implementing them), selected by
   `spring.cloud.function.definition` (including `a|b` composition), with bindings renamed through
   `spring.cloud.stream.function.bindings.*`, destinations and groups from
   `spring.cloud.stream.bindings.*`, and `StreamBridge.send`. The binder (Kafka or RabbitMQ) comes
   from the binding's `binder`, `default-binder`/`binders.*.type`, a binder dependency in the build
   file, or binder-specific config keys; when none of those decide it, the binding is labelled
   `stream` and only matches other Stream bindings. Not covered:
   - the annotation model (`@EnableBinding`, `@StreamListener`, `@Input`/`@Output`), removed in
     Spring Cloud Stream 4;
   - functions with several inputs or outputs (`Tuple`/`Flux[]`, bindings `-in-1`, `-out-1`),
     including multi-output Kafka Streams processors: only index 0 is read;
   - `spring.cloud.function.routing-expression` and the routing function;
   - binder dependencies declared through a version catalog (`libs.x.y`) or a BOM-only parent:
     use `default-binder` or binder config keys, or the binding shows as `stream`;
   - on the RabbitMQ binder, the queue a group creates (`<destination>.<group>`) is not matched to
     a plain `@RabbitListener` on that queue.
4. **A topic name that is a runtime value** (a method parameter, a computed string) is shown as
   `<name>`, listed under "Channel not known statically", and matched to nothing.
   `kafkaTemplate.send(new ProducerRecord<>(...))` is listed the same way.
5. **RabbitMQ is matched by routing key = queue name.** Exchange bindings are not read, so a
   producer whose routing key differs from the queue name it ends up in is not linked to it.
6. **Brokers are not compared.** Two services using the same topic name on different Kafka
   clusters are still shown as producer and consumer of one channel.

## Data

1. **Entities:** `@Entity` (table from `@Table`, else Spring Boot's default snake_case naming),
   `@Document` (collection from `collection`/`value`, else the decapitalized class name), Spring Data
   JDBC/R2DBC `@Table`, `@RedisHash`. A custom `PhysicalNamingStrategy` or
   `spring.jpa.hibernate.naming.*` setting is not applied.
2. **Repositories** are linked to their entity through the first type argument of the Spring Data
   interface they extend directly (or of an indexed intermediate repository interface).
3. **SQL inside strings is not read.** A `JdbcTemplate`/`MongoTemplate`/`EntityManager` user is
   listed as "direct" access, but which tables its queries touch is not known, and `@Query` texts
   are not parsed.
4. **Migrations** are matched to entities by table name within the same repository root. Flyway
   Java migrations and Liquibase YAML/JSON changelogs are not read.
5. **Datasource details are limited to kind and database name** on purpose: hosts, users and
   passwords in URLs are never shown in answers.

## Impact analysis

1. **Callers are found through the call graph,** so the limits under Indexing apply: a call through
   a chained expression, an overload, or reflection is not seen. Treat "Called by (0)" as "no
   call the index can see", and confirm with a text search before deleting code.
2. **Entry points walk callers up to 8 levels.** Deeper chains are cut off.
3. **Tests** are found by reference (a field, call receiver or import) or by the `<Name>Test`,
   `<Name>Tests`, `<Name>IT` naming convention. Tests that only reach the target indirectly, through
   a Spring context, are not listed.

## Conversation threads

1. **A new chat is not visible to the server.** Copilot runs one agentideate process per IDE, so a
   second chat on the same topic within 6 hours continues the thread, and the answers leave out what
   the first chat was given. Every such answer says so and how to get it in full (`thread: "new"`);
   `thread_recall` restores a topic for a new chat and resets what counts as given.
2. **Relatedness is a heuristic:** overlap of cited files plus overlap of words, threshold 0.3. Two
   questions about different features that touch the same central class (an `OrderService` used by
   everything) can be joined; a follow-up phrased with entirely new words about files not cited yet
   can start a new thread. `thread: "<id>"` and `thread: "new"` override it.
3. **Only list lines and call trees are deduplicated.** Overviews, data maps and slices are given in
   full each time (with known files shortened to their name).
4. **Threads are per workspace and kept for 30 threads**, oldest dropped first.

## Task slices

1. **Seeds are found by words,** the same matching as `locate` with simple stemming. A task that
   names nothing the code names ("make checkout faster") finds nothing, and says so.
2. **Collaborators are one hop:** injected beans and their implementations, a repository's entity,
   interfaces, direct callers. Utilities called statically and deeper chains are not included; the
   skeleton's `->` lists show where to look next.
3. **At most 3 method bodies of up to 40 lines each** are included, and only those scoring within 60%
   of the best match. A long method is cut, with the remaining line count shown.

## Log digests

1. **Recognized:** Gradle and Maven outcomes, Gradle "What went wrong", javac errors in both
   formats (with symbol/location), Gradle test console failures, Maven Surefire failure summaries,
   Java stack traces with cause chains (frames shown only when the class is in the index), Spring
   Boot "APPLICATION FAILED TO START" reports, JUnit XML reports (Gradle and Maven locations).
2. **Not recognized:** Kotlin compiler errors, Gradle configuration-cache problems, dependency
   resolution failures beyond the "What went wrong" text, non-English build output, test frameworks
   that do not write JUnit XML.
3. **JUnit reports may be stale.** The digest says how old the newest report is; a report written
   before the last change describes the old code.
4. **Only files inside the workspace are read** through `path`; anything else is refused.

## Workspace

1. **Project detection depends on the client.** When Copilot reports the open project as MCP roots,
   no configuration is needed. If the IntelliJ Copilot plugin does not send roots, the agent passes
   the folder as the `repo` argument, or a `--repo` is added to `mcp.json`. Whether the plugin sends
   roots has not been verified on a real IntelliJ install yet.
2. **The whole workspace is re-scanned on every call** to detect changes (directory walk plus size
   and mtime per file; only changed files are re-read). Fast for typical service repos; very large
   monorepos may want a file watcher instead (not implemented).
3. **Several IntelliJ windows on the same project** share one cache directory. Two servers writing
   it at the same moment is safe (atomic replace) but one may redo the other's work.

## Savings figures

1. **The footer is an estimate.** "~N tokens to open" is the size of the cited files at ~4
   characters per token: what reading every cited file would have cost. The agent might not have
   opened all of them, or might have opened more. M4's benchmark compares real agent runs with and
   without agentideate.
2. **The tool definitions are a fixed cost.** Copilot sends every MCP tool's name, description and
   argument schema with each agent request: about 1,300 tokens for agentideate's 9 tools. One
   avoided file read usually covers it; descriptions are kept short for this reason. If credits are
   counted per request rather than per token, it adds no requests.
3. **The overview cites only the files it names** (controllers, applications, config), although it
   summarizes all of them, so its figure understates what it replaces.
