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
- It does not follow symbolic links out of the project.

## What it writes

- Only under `~/.agentideate/` (or `AGENTIDEATE_HOME`): an index cache (`index.json`) and a usage
  log (`ledger.jsonl`, recording tool name, timing and sizes, never code or questions) per workspace.
- **Nothing in the project.** The current version is entirely read-only with respect to source
  code. Later versions that can apply an approved change will write only after two explicit
  developer actions, only inside the registered project folders, with an audit log.

## What it never does

- **No network.** It opens no sockets and makes no HTTP calls. (A later version will serve a local
  approval page on `127.0.0.1` only.)
- **No AI.** It never calls a model or any external service; answers are computed from source.
- **No telemetry.** Nothing leaves the machine.
- **No command execution.** The current version runs no processes. (Later versions will run an
  allowlisted build command, `./gradlew compileJava`, with a timeout, after approval.)

## What is inside the jar

| Dependency | Purpose | License |
|---|---|---|
| `com.github.javaparser:javaparser-core` 3.26.3 | Parse Java source | Apache 2.0 / LGPL |
| `com.fasterxml.jackson.core:jackson-databind` 2.18.2 (+ core, annotations) | JSON for MCP and the cache | Apache 2.0 |

Everything else is agentideate's own code, in this repository. The MCP protocol is implemented
directly (no SDK) to keep this list short. Releases will publish a SHA-256 checksum and an SBOM, so
the copy in Artifactory can be verified against the GitHub release.

## Data flow

```text
Copilot (IntelliJ)  --stdin-->  agentideate  --reads-->  project files
                    <--stdout--              --writes--> ~/.agentideate/ (cache, usage log)
```

The answers it returns to Copilot contain code structure (class, method and route names, file
paths and line numbers). Copilot then sends what it needs to its model as it would with any open
file, so agentideate adds no new destination for code; it reduces how much code Copilot sends.
