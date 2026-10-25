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
6. **Non-Java files are listed, not read.** `application.yml`, `.properties`, SQL migrations and
   build files are indexed by path only (found by `locate`, shown in the overview). Their contents
   (datasource URLs, topic names in config) are not used yet: planned for M2.
7. **Files over 2 MB are listed but not parsed** (generated sources, mostly). They are counted as
   unparseable in the coverage line.
8. **Constants:** a route path or attribute given as a `static final String` constant is resolved,
   including concatenation (`API + "/orders"`) and static imports. A value computed at runtime, or a
   Spring property placeholder (`${pricing.url}`), is kept as written; an unresolved constant shows
   as `${NAME}` instead of being read as literal path text.

## Spring semantics

1. **Bean detection is annotation-based.** `@Component`/`@Service`/`@Repository`/`@Controller`/
   `@RestController`/`@Configuration`, Spring Data repository interfaces, Feign clients. `@Bean`
   factory methods and beans registered through `@Import` or XML are not modelled yet.
2. **Interface dispatch lists every implementation.** `@Primary`, `@Qualifier` and profile
   conditions are not used to pick one; a call on an interface with two implementations shows both.
3. **Only the first class-level `@RequestMapping` path is used** when several are given.
4. **Kafka, MongoDB and Postgres are not mapped yet.** A `KafkaTemplate.send` or `@KafkaListener`
   is not yet a hop in `endpoint_flow`, and entities are not linked to their tables or collections.
   M2 adds these.
5. **Feign hops are matched by HTTP method and path template only,** against routes in a different
   module. The Feign client's `name` is shown but not yet used to choose the target service when two
   services expose the same path.

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
2. **The overview cites only the files it names** (controllers, applications, config), although it
   summarizes all of them, so its figure understates what it replaces.
