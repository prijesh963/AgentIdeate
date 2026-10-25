# Security fact sheet

For whoever has to approve agentideate for use. One page: what it reads, what it writes, what it
never does.

## What it is

A Java program (`agentideate.jar`) that GitHub Copilot starts on the developer's machine as a local
MCP server. Copilot sends it questions over stdin and reads answers from stdout. It parses Java
source code to answer structural questions about the open project.

## What it reads

- Files under the project folders it is pointed at (by Copilot, by `--repo`, or by the `repo`
  argument of a tool call): `.java` sources and configuration/build files (`.yml`, `.yaml`,
  `.properties`, `.gradle`, `.kts`, `.xml`, `.sql`, `.avsc`, `.proto`).
- It skips `.git`, `build`, `target`, `out`, `node_modules`, IDE folders and similar.
- Spring config files (`application*.yml`/`.properties`) are read to resolve topic names and
  identify datastores. Before anything is cached, values of secret-looking keys (password, secret,
  token, credential, api/private/access key) are replaced with `<redacted>` and `user:password@`
  in URLs is masked. Answers show topic and queue names, table names and a datasource's kind and
  database name, never hosts, users or passwords.
- It does not follow symbolic links out of the project.
- `compress_log` reads a log file only when its real path is inside a project folder, and JUnit XML
  reports from the projects' own `build/test-results` and `target/*-reports` folders, with DTDs and
  external entities disabled so a report cannot pull in other files.

## What it writes

- Only under `~/.agentideate/` (or `AGENTIDEATE_HOME`), per workspace:
  - `index.json`: the index cache (code structure, redacted config values);
  - `ledger.jsonl`: a usage log of tool name, timing, sizes and thread id, never code or questions;
  - `threads.json`: conversation threads, which hold the questions asked (tool arguments such as a
    route path or a task description), the answer lines already given, and the paths of cited files.
    The 30 most recent threads are kept.
  - `plans/p*.json`: plans the agent proposed (the code it quotes and proposes), their versions,
    decisions and approvals.
  - `runs/`, `audit.jsonl`: implementation runs with backups, and the append-only audit log (writes,
    undos, reviews).
  - `reviews/p*.json`: review verdicts and the findings the agent recorded (file, line, text).
  - `tests/p*.json`: the number of failed test runs per plan, with a one-line history.
  - `client.json` (in the home folder itself): the name, version and capabilities of the last MCP
    client that started agentideate, so `setup` can report them.
- **In the project, only an approved plan, and only after it is started.** Asking questions,
  drafting and approving plans write nothing to the project. `plan_implement` (or `agentideate
  implement`) writes the files of an approved plan, after a second, explicit Start from the
  developer, only inside the registered project folders, and only if every file is unchanged since
  approval. Originals are backed up under `~/.agentideate/workspaces/<id>/runs/<run>/backup/`, any
  failure restores them, and every write and undo is appended to `audit.jsonl`.

## Approval

A plan is approved by the developer, never by the agent:

- through a form the Copilot client shows in the chat (MCP elicitation); the answer travels from the
  client to agentideate without passing through the model;
- or, when the client cannot show forms, through the client's own confirmation of the
  `plan_approve` call (the tool declares itself as not read-only so the client asks); a developer who
  sets that tool to "always allow" gives this up;
- or, with `--approval terminal`, only by `java -jar agentideate.jar approve <plan>` typed in a terminal.

A version that does not validate against the current code cannot be approved, and an approval
covers exactly the approved content (by hash). Implementation needs that approval and, separately,
a Start from the developer, given the same way (chat form, tool confirmation, or terminal).

## What it never does

- **No network.** It opens no sockets and makes no HTTP calls. (A later version will serve a local
  approval page on `127.0.0.1` only.)
- **No AI.** It never calls a model or any external service; answers are computed from source.
- **No telemetry.** Nothing leaves the machine.
- **No command but an allowlisted few.** Processes are started without a shell, and only these,
  matched exactly: `git status` / `git rev-parse` with read-only flags (to see what changed); the
  module's Gradle build with `compileJava compileTestJava`, or `test` filtered with `--tests <class>`;
  its Maven build with `test-compile`, or `test` filtered with `-Dtest=<classes>`. A test filter must
  be a plain class name pattern (letters, digits, `.`, `$`, `*`, `#`); anything else, including any
  other flag or property, is refused before it starts. Each has a timeout and capped output. Builds
  run when an approved plan is implemented (the compile check, which `AGENTIDEATE_COMPILE=off` turns
  off) and when `test_run` is called, which Copilot confirms because it is not read-only. A build
  runs the project's own build scripts and a test run the project's tests, as running them in the
  IDE would.

## What is inside the jar

| Dependency | Purpose | License |
|---|---|---|
| `com.github.javaparser:javaparser-core` 3.26.3 | Parse Java source | Apache 2.0 / LGPL |
| `com.fasterxml.jackson.core:jackson-databind` 2.18.2 (+ core, annotations) | JSON for MCP and the cache | Apache 2.0 |

Everything else is agentideate's own code, in this repository. The MCP protocol is implemented
directly (no SDK) to keep this list short. Each release publishes `agentideate.jar.sha256` and a
CycloneDX SBOM (`agentideate-sbom.json`) generated by the build, so the copy in Artifactory can be
verified against the GitHub release and the bundled libraries checked against your policies
(see `docs/RELEASING.md`).

## Data flow

```text
Copilot (IntelliJ)  --stdin-->  agentideate  --reads-->  project files
                    <--stdout--              --writes--> ~/.agentideate/ (cache, usage log)
```

The answers it returns to Copilot contain code structure (class, method and route names, file
paths and line numbers). Copilot then sends what it needs to its model as it would with any open
file, so agentideate adds no new destination for code; it reduces how much code Copilot sends.
