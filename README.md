# agentideate

Local repo intelligence for GitHub Copilot in IntelliJ, built for Java / Spring Boot microservices.

Copilot's agent mode spends most of its credits **exploring**: opening files, grepping, re-reading
code to work out how a repo is wired. agentideate indexes the workspace on your machine and gives
the agent short, exact answers instead, with `file:line` for everything, so the agent stops
exploring and the credits go to the actual work.

```text
> How does POST /api/orders work?

POST /api/orders  (order-service)
  OrderController#placeOrder  order-service/.../web/OrderController.java:32
    OrderService.placeOrder -> OrderServiceImpl#placeOrder  order-service/.../service/OrderServiceImpl.java:30
      PricingClient#getPrice [feign -> pricing-service GET /api/prices/{sku}]  order-service/.../PricingClient.java:10
        => handled in pricing-service:
          PriceController#getPrice  pricing-service/.../web/PriceController.java:20
            PriceService#currentPrice  pricing-service/.../service/PriceService.java:16
      OrderRepository#save [spring-data-repository, inherited from Spring Data]  ...
      OrderEventPublisher#publishCreated  order-service/.../messaging/OrderEventPublisher.java:17
-- agentideate: from 8 source files (~1.7k tokens to open) -> ~396 tokens here
```

Every answer ends with that footer line: what the answer stood in for, and what it cost instead.

- **No AI inside.** agentideate never calls a model. Answers come from parsing your code.
- **Nothing added to your project.** No config files, nothing to commit. State lives in `~/.agentideate`.
- **No network.** It reads local files and talks to Copilot over stdin/stdout. See [docs/SECURITY.md](docs/SECURITY.md).

## Setup (once per machine, about 5 minutes)

You need IntelliJ IDEA with the GitHub Copilot plugin (agent mode with MCP support). Recent
IntelliJ versions bundle Java 21, which is all agentideate needs.

**1. Download the jar.** Get `agentideate.jar` from your team's Artifactory (or this repo's
Releases page) and put it in a folder in your home directory:

```text
Windows   C:\Users\<you>\.agentideate\agentideate.jar
macOS     /Users/<you>/.agentideate/agentideate.jar
Linux     /home/<you>/.agentideate/agentideate.jar
```

**2. Add it to Copilot's MCP configuration.** In IntelliJ, open Copilot's MCP settings
(*Settings → Tools → GitHub Copilot → Model Context Protocol (MCP) → Configure*, or the tools icon
in Copilot Chat's agent mode → *Add more tools*; the exact location varies by plugin version).
This opens `mcp.json`. Add the `agentideate` entry, with your jar's path:

```json
{
  "servers": {
    "agentideate": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "C:\\Users\\<you>\\.agentideate\\agentideate.jar"]
    }
  }
}
```

If `java` is not on your PATH, use IntelliJ's bundled Java as the `command` instead:

```text
Windows   C:\Program Files\JetBrains\IntelliJ IDEA <version>\jbr\bin\java.exe
macOS     /Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java
```

**3. Restart IntelliJ.** In Copilot Chat, switch to **Agent** mode; the tools icon lists
`repo_overview`, `locate`, `endpoint_flow`, `messaging_map`, `data_map` and `impact_of`.

That's it. Open your project as usual and ask Copilot about it. The first question indexes the
project (seconds for a small repo, a minute or two for a large one); after that only changed files
are re-read.

### Several repositories at once

If your feature spans several microservice repos, open them in one IntelliJ window and Copilot
passes all of them. Or pin them in `mcp.json`:

```json
"args": ["-jar", "/path/agentideate.jar", "--repo", "/work/order-service", "--repo", "/work/pricing-service"]
```

### Update and uninstall

- **Update:** replace the jar, restart IntelliJ.
- **Uninstall:** delete the `agentideate` entry from `mcp.json` and the `~/.agentideate` folder.

## What it can answer today

| Tool | Ask Copilot things like | Returns |
|---|---|---|
| `repo_overview` | "What is in this repo?" | services, build tool, REST routes by controller, beans, datastores and tables, Kafka topics produced/consumed, Feign calls out, config files |
| `locate` | "Where is stock reserved?", "Is invoice approval already implemented?" | matching classes, methods, routes, topics, tables, config files with `file:line`, or a plain **not found** with what was searched |
| `endpoint_flow` | "How does `PUT /api/orders/{id}/cancel` work?" | the call tree from controller through services, interfaces resolved to implementations, Feign calls and Kafka/RabbitMQ/JMS messages followed into the other service |
| `messaging_map` | "Who consumes `order.created`?" | every topic/queue with its producers and consumers across services (`KafkaTemplate`, `@KafkaListener`, RabbitMQ, JMS, Spring Cloud Stream functions and `StreamBridge`); topic names resolved from constants and `application.yml` |
| `data_map` | "Which tables does order-service use?" | per service: datasource, entity → table/collection, repositories, Flyway/Liquibase migrations, direct `JdbcTemplate`/`MongoTemplate` use |
| `impact_of` | "What breaks if I change `OrderEntity`?" | callers, injectors, the routes and Kafka consumers it is reached from, messages it sends, tables and migrations, tests, across services |

The same tools work from a terminal, which is handy for checking an answer:

```bash
java -jar agentideate.jar overview --repo /work/shop
java -jar agentideate.jar locate "stock reserve" --repo /work/shop
java -jar agentideate.jar flow /api/orders POST --repo /work/shop
java -jar agentideate.jar messaging --repo /work/shop
java -jar agentideate.jar data orders --repo /work/shop
java -jar agentideate.jar impact OrderServiceImpl#placeOrder --repo /work/shop
```

Gaps are listed in [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md). Where agentideate is
heading (stateful Q&A, plans with an approval gate, implementation, review, tests, debugging) is in
[docs/DESIGN.md](docs/DESIGN.md).

## Building it yourself

```bash
./gradlew build          # compile + all tests
./gradlew shadowJar      # build/libs/agentideate.jar
```

Inside an office network, resolve dependencies through Artifactory without editing the build:

```bash
./gradlew build -Pagentideate.mavenMirror=https://artifactory.example.com/artifactory/maven-remote
```

The Gradle wrapper itself downloads Gradle from `services.gradle.org`; if that is blocked, change
`distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to your Artifactory's Gradle mirror.
