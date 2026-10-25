# agentideate

Local MCP server that gives GitHub Copilot (IntelliJ, agent mode) exact, small answers about Java /
Spring Boot microservice repos, so agent credits go to work instead of exploration.
Design and milestones: docs/DESIGN.md. Gaps: docs/KNOWN_LIMITATIONS.md.

## Build

```bash
./gradlew build        # compile + tests (must pass before pushing)
./gradlew shadowJar    # build/libs/agentideate.jar
./gradlew run --args="overview --repo samples/shop"
```

If Maven Central answers 429, add `--max-workers=1`.

## Rules

- Java 21, Gradle Kotlin DSL, one self-contained jar. Runtime dependencies stay minimal
  (javaparser-core, jackson-databind); each new one needs a reason, because it has to pass review.
- agentideate never calls a model. Every answer is computed from source.
- Never write into the developer's project. State goes under `AppHome` (`~/.agentideate`).
  Tests use a temp `AppHome`, never the real home.
- stdout carries MCP protocol messages only; diagnostics go to stderr.
- MCP and CLI both go through `ToolRegistry.call`. Don't add logic to `Main` or `McpServer`.
- Answers: plain text, `file:line` for every claim, explicit "Not found" with what was searched,
  under the `ToolRegistry.BUDGET_CHARS` cap. Tool descriptions stay short (sent on every turn).
- An ambiguous name resolves to nothing, not to a guess.
- `samples/shop` is the fixture for tests and the benchmark; it is never compiled.
- When a test fails, fix the cause or the fixture, never relax the assertion.
- Record new gaps in docs/KNOWN_LIMITATIONS.md.
