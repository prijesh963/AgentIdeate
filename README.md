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

**2. Add it to Copilot's MCP configuration.** `java -jar <path>/agentideate.jar setup` checks
your machine and prints the exact entry for your jar and your Java; it changes nothing itself. In
IntelliJ, open Copilot's MCP settings
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
`repo_overview`, `locate`, `endpoint_flow`, `messaging_map`, `data_map`, `impact_of`,
`slice_for_task`, `compress_log`, `thread_recall`, `plan_submit`, `plan_show`, `plan_approve`,
`plan_implement` and `plan_rollback`.

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
| `slice_for_task` | "Add a discount when placing an order" | the classes the task is about and their collaborators as signature skeletons with line numbers, entry points, and the source of only the key methods: the context for writing the change, in one call |
| `compress_log` | "Why did the build fail?" | a Gradle/Maven/test/Spring Boot failure reduced to errors with `file:line`, from a log file, pasted text, or the JUnit XML reports |
| `thread_recall` | "Continue the invoice approval work" | an earlier topic's questions and central files, to pick it up in a new chat |
| `plan_submit` | "Plan: add invoice approval" | the agent's plan, checked against the code: every edit as "lines 38-38, from this to this", new files in full, problems that block approval, and cross-service effects to look at |
| `plan_show` | "Show me the plan again" | the plan re-checked against today's code, every version and decision |
| `plan_approve` | (after review) | an Approve / Request changes form in the Copilot chat; your click, not the agent's |
| `plan_implement` | "Implement p1" | after your *Start* in the chat: the approved plan written exactly, every file verified, the build compiled; rolled back on any failure |
| `plan_rollback` | "Undo that" | the run undone from its backups, never overwriting a file edited since |
| `plan_review` | "Review p1" | whether the project holds exactly the approved changes and nothing else (CONFORMS / DEVIATES / INCOMPLETE), then the changed code with its callers and tests to review; findings are checked against real lines |
| `test_pack` | "Write unit tests for p1" | each changed method's code, what to mock, the test class to extend (or where a new one goes), your team's test conventions and an existing test to copy |
| `test_run` | "Run the tests for p1" | only the affected test classes, run with your build; failures with `file:line`; stops after 3 failed runs |

### Plans: nothing is written until you approve

To change code, the agent first submits a plan with `plan_submit`. Each change is an **edit** that
quotes the exact current text it replaces, an **add** with the new file's content, or a **delete**.
agentideate checks every version against the files as they are now, and refuses to call a plan valid
when it is not:

- a quoted text that does not match exactly once (with a hint when only the spacing differs);
- an edit that leaves a Java file unparseable, an added file that already exists or sits in the
  wrong package folder, a deleted file that other code still uses;
- evidence (`OrderServiceImpl#placeOrder`) that is not in the code;
- a change that breaks one of your decisions.

It also flags, without blocking, code in other services that depends on what the plan changes (the
consumers of a Kafka event the plan touches, for instance).

You give feedback in the chat; the agent revises with a patch (only the changes that change; the
rest carry over untouched) and records any rule you state as a **decision**, which every later
version is shown and checked against (`"Messaging stays as it is"`, ruling out `**/messaging/**`).

Approval is your action, per version:

- **In the chat:** `plan_approve` asks Copilot to show a form with *Approve vN* / *Request changes*.
  Your answer goes from Copilot to agentideate directly; the model never produces it.
- **If your Copilot plugin cannot show forms**, `plan_approve` is marked as a tool that changes
  something, so Copilot asks you to confirm the call; that approval is recorded as such.
  `java -jar agentideate.jar setup` shows whether your plugin supports the form.
- **Strictest:** add `"--approval", "terminal"` to the agentideate entry's `args` in `mcp.json`; then
  only `java -jar agentideate.jar approve p1` in a terminal approves.

A plan version with problems against the current code is never approved, and any revision needs a
new approval.

### Implementation: exactly what was approved, or nothing

`plan_implement` asks for a second, separate decision, *Start: write N files*, in the chat (or
Copilot's confirmation, or `java -jar agentideate.jar implement p1` in terminal mode). Then:

1. **Gate.** The approval must cover the latest version's exact content, and every file the plan
   touches must be byte-for-byte what it was at approval. Otherwise nothing is written: the plan no
   longer describes the code, so it is revised and approved again.
2. **All or nothing.** Every new file content is computed first; originals are backed up; each file
   is written atomically, keeping its line endings; a failure part-way restores every file.
3. **Verify.** Each file is re-read and checked against what was meant; `git status` before and
   after shows anything that changed outside the plan; the changed modules are compiled with their
   own Gradle or Maven build (`compileJava compileTestJava` / `test-compile`). Code that does not
   compile is rolled back automatically; a build that fails for another reason (a dependency it
   could not download) keeps the changes and says so.
4. **Record and undo.** Each run is kept with its backups and written to an append-only audit log.
   `plan_rollback` (or `java -jar agentideate.jar rollback r1`) undoes it, but never overwrites a
   file edited after the run.

Set `AGENTIDEATE_COMPILE=off` on machines that cannot build (or pass `compile: false`).

### Review and tests

`plan_review p1` checks the project against the approved plan without any judgement: every file of
the run must still be exactly what was written, every approved change must be there, and git must
show nothing else changed since the run started (changes you had before it are not counted). The
verdict is **CONFORMS**, **DEVIATES** (with what) or **INCOMPLETE**. The answer also hands the agent
the changed code as it is now, with the callers and tests of each changed method, for a logic
review; the agent sends its findings back with `file` and `line`, and a finding that points at a
line that does not exist is rejected, not recorded.

`test_pack p1` gives the agent what it needs to write unit tests in one go: the changed methods,
the collaborators to mock, the existing test class (or where a new one goes), and how your team
writes tests (JUnit 5 or 4, Mockito with `@ExtendWith`, AssertJ, Spring slices, Testcontainers),
with one of your own tests as the example. The tests are written as a plan, so you approve them
too. `test_run p1` then runs only those test classes (`--tests` / `-Dtest`) with your build and reads
the reports of that run. After 3 failed runs for a plan it stops and asks the agent to report; you
allow more with `java -jar agentideate.jar tests p1 --reset`.

### It remembers the conversation

Answers belong to threads. A question related to the last few (sharing files or words, on the same
branch, within 6 hours) continues that thread, and an unrelated one starts a new thread; the footer
says which (`thread t1 continued` / `thread t2 new`). Inside a thread nothing is repeated: a file
already cited is shown by its file name, a hit or caller already listed is left out (and the next one
shown instead), a call tree already expanded is only named. Anything from a file that changed since
is given in full again. If the agent no longer has the earlier answers (a new chat), it asks with
`thread: "new"`, or recalls the topic with `thread_recall`.

The same tools work from a terminal, which is handy for checking an answer:

```bash
java -jar agentideate.jar overview --repo /work/shop
java -jar agentideate.jar locate "stock reserve" --repo /work/shop
java -jar agentideate.jar flow /api/orders POST --repo /work/shop
java -jar agentideate.jar messaging --repo /work/shop
java -jar agentideate.jar data orders --repo /work/shop
java -jar agentideate.jar impact OrderServiceImpl#placeOrder --repo /work/shop
java -jar agentideate.jar slice add a discount when placing an order --repo /work/shop
java -jar agentideate.jar log build/test.log --repo /work/shop
java -jar agentideate.jar recall
java -jar agentideate.jar plans                                   # list plans
java -jar agentideate.jar approve p1                              # read the plan, approve it in the terminal
java -jar agentideate.jar implement p1                            # write it (asks y/N), verify, compile
java -jar agentideate.jar rollback r1                             # undo that run
java -jar agentideate.jar review p1                               # check what was written against the approval
java -jar agentideate.jar testpack OrderServiceImpl#placeOrder    # what is needed to test it
java -jar agentideate.jar tests p1                                # run only p1's tests (--reset: allow 3 more failed runs)
```

Gaps are listed in [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md). Where agentideate is
heading (stateful Q&A, plans with an approval gate, implementation, review, tests, debugging) is in
[docs/DESIGN.md](docs/DESIGN.md).

## Measuring what it saves

```bash
java -jar agentideate.jar bench benchmark/shop.json --repo samples/shop   # tasks vs the files they replace
java -jar agentideate.jar report --repo /work/shop                         # what Copilot actually used, last 7 days
```

On the sample, answers are 64% smaller than even the minimum set of files holding the same facts,
with every expected fact present; the sample's files are small, so measure on your own repository
with your own tasks. See [docs/BENCHMARK.md](docs/BENCHMARK.md).

## Releases

Each `v*` tag publishes the jar, its SHA-256 and an SBOM as a GitHub Release, ready for Artifactory.
See [docs/RELEASING.md](docs/RELEASING.md).

## Building it yourself

```bash
./gradlew build          # compile + all tests
./gradlew shadowJar      # build/libs/agentideate.jar
./gradlew release        # jar + SHA-256 (build/libs) and SBOM (build/sbom)
```

Inside an office network, resolve dependencies through Artifactory without editing the build:

```bash
./gradlew build -Pagentideate.mavenMirror=https://artifactory.example.com/artifactory/maven-remote
```

The Gradle wrapper itself downloads Gradle from `services.gradle.org`; if that is blocked, change
`distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` to your Artifactory's Gradle mirror.
