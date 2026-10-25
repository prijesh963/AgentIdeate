# agentideate design

## The problem

The team works in IntelliJ with GitHub Copilot as its only AI tool, on Java / Spring Boot
microservices (Kafka, MongoDB, Postgres and other datastores), with a fixed monthly budget of
agent-mode credits per developer. Agent mode burns those credits fast because every task starts
with exploration: the model opens files, searches, and re-reads code to rediscover how the repo is
wired, on every request, and does so again after each round of feedback.

Instruction files (`copilot-instructions.md` and the like) cannot fix this. They can tell a model
to be brief, but they cannot tell it what the code says.

## The approach

Move the knowing out of the model and onto the developer's machine.

agentideate is a local MCP server, started by Copilot itself, that indexes the workspace and
answers structural questions deterministically: which routes exist, what a route calls, which
implementation an injected interface reaches, which service a Feign client calls, where a thing
lives, and whether it exists at all. An agent that would have opened thirty files gets a 300-token
answer with `file:line` for everything.

This pays twice:

- A **premium model** spends its credits on reasoning about the change, not on reading.
- A **cheaper model** gets a much easier job: the hard part (finding and connecting the code) is
  already done, so the gap to a premium model narrows.

### Principles

1. **No model inside.** agentideate never calls an LLM. Every answer is computed from source, so
   it is cheap, fast, repeatable and cannot hallucinate.
2. **Every claim is checkable.** Answers carry `file:line`. Absence is stated explicitly, together
   with what was searched, so "not found" is never confused with "not looked for".
3. **Small answers by construction.** Plain text rather than JSON, a hard size cap per answer, data
   types summarized rather than listed, and short tool descriptions (those are sent on every turn).
4. **Zero footprint in the project.** Nothing is written into the developer's repo. State lives in
   `~/.agentideate/workspaces/<id>/`.
5. **One path for every caller.** MCP and the CLI call the same `ToolRegistry.call`, so they cannot
   give different answers to the same question.
6. **Easy to approve.** No network access, no telemetry, two runtime dependencies, read-only until
   a developer explicitly approves a write.
7. **Prove it.** Every answer states what it replaced (the footer) and is logged to
   `ledger.jsonl`, so savings are measured, not claimed.

## Architecture

```text
 IntelliJ ── Copilot agent mode ── MCP (stdio, JSON-RPC) ──┐
                                                           ▼
 Terminal ─────────────── CLI ──────────────────► ToolRegistry.call
                                                           │
             ┌─────────────────────────────────────────────┤
             ▼                                             ▼
     WorkspaceService                               Tools (no model)
     which repos? (tool arg > --repo >              repo_overview · locate ·
     AGENTIDEATE_REPOS > MCP roots > cwd)           endpoint_flow · ...
             │                                             ▲
             ▼                                             │
     WorkspaceIndex ── JavaFileParser ──► CodeGraph ───────┘
     incremental: size+mtime,             cross-file / cross-repo:
     then hash, then parse;               type resolution, beans,
     cache in index.json                  routes, implementations,
                                          calls, Feign hops
```

| Package | Responsibility |
|---|---|
| `index` | Scan roots, parse Java with JavaParser (syntax only), read Spring config and migrations, incremental cache |
| `graph` | Resolve names across files and repos; beans, routes, implementations, calls; `Integrations` (config, messaging, data); `ReverseIndex` (callers, injectors, entry points, tests) |
| `workspace` | Decide which repos a request is about; reuse the graph until something changes |
| `tools` | The tools, the answer budget, the savings footer, the ledger |
| `session` | Conversation threads and the store that continues or starts them |
| `mcp` | Hand-written MCP stdio server (initialize, tools, ping, roots) |

Built with Gradle (Kotlin DSL) on Java 21, shipped as one self-contained jar.

## Workflows

Six workflows, each a set of tools. Workflows 1 to 2 only read; nothing is written to the project
before Workflow 3, and Workflow 3 needs two explicit developer actions.

```text
                         ┌───────────────────────────────────────┐
                         │  INDEX + GRAPH  (local, incremental)  │
                         │  beans · endpoints · topics · DBs ·   │
                         │  cross-repo links · file hashes       │
                         └──────────────┬────────────────────────┘
                                        │ every workflow reads from here
   ┌────────────────────────────────────┼─────────────────────────────────┐
   ▼                                    ▼                                 ▼
 [1 UNDERSTAND / ASK]            [2 PLAN]  (read-only)             [6 DEBUG]
  question ─► thread match        user story                        failure/stack trace
   same topic? ─yes─► delta        │                                  │ compress + map to graph
   no ─► new thread                ▼                                  ▼
  answer + file:line refs     gather_context (repo-wide)           recent changes touching
                                   │                                the failing frames
                                   ▼                                  │
                              model drafts plan ◄──────┐              ▼
                                   │                   │          fix proposed AS A PLAN
                                   ▼                   │          └──────► back into [2]
                            SERVER VALIDATES           │
                            paths · symbols · lines ·  │ reject with exact
                            "before" text · evidence   │ mismatch
                                   │ pass              │
                                   ▼                   │
                            developer reviews ─ feedback ─► decision ledger
                                   │                        (v1 → v2 → v3, patch-based,
                                   │ Approve (button)        checked against ledger)
                                   ▼
                          [3 IMPLEMENT]  ← second button: Start
                          pre-flight: file hashes still match?
                            no ─► STOP, re-plan (nothing written)
                          snapshot ─► apply (modify / add / delete)
                          delete: graph checks references first
                            any failure ─► ROLLBACK
                                   ▼
                          verify: re-read each change · diff vs plan · gradle compile
                                   ▼
                          [4 REVIEW]
                          deterministic: approved vs applied · extras · missing · drift
                                       · cross-repo impact re-check
                          model: logic/edge-case review of the diff only
                                   │ deviations ─► back to [2] as a revision
                                   ▼
                          [5 UNIT TESTS]
                          conventions from build.gradle + sibling tests
                          model writes ─► test plan ─► approval ─► write
                          run only affected tests ─► failures compressed
                          bounded fix loop (max 3) ─► honest report
                                   ▼
                          SAVINGS LEDGER + session timeline (resume anywhere)
```

### 1. Understand and ask (stateful)

Ask anything about the repo: how something is implemented, where a file lives, whether a feature
already exists and which files it touches.

The MCP server cannot see Copilot's chat, so it keeps its own memory per topic:

- **Threads.** Each line of questioning is a thread holding its questions, the entities already
  resolved (files, beans, topics, routes) and compact summaries of what was answered.
- **Relatedness without a model.** A new question is scored against the thread's entity set by
  graph distance and word overlap. Close enough continues the thread; otherwise a new one starts.
  The answer says which (`continuing: invoice approval` / `new topic`).
- **Delta answers.** A follow-up returns only what is new and notes what was already given, so
  follow-ups get cheaper as a thread grows.
- **Control.** The agent can pass a thread id; the developer can say "new topic".
- Threads expire after a time window or on a branch change; cached facts are re-checked against
  the index when files change.

As built (M3): relatedness is the overlap of the files the answer cites with the thread's files
(shared over the smaller set) plus half the overlap of question words, against threads on the same
branch active within 6 hours; 0.3 or more continues. Each answer is computed twice when threaded:
once to learn the files it cites (which picks the thread), once to render it against that thread.
Remembered lines and call trees carry the files they came from, so an edit anywhere beneath them
brings them back in full.

### 2. Plan, with a feedback loop until approval

From a user story, the model drafts a plan. agentideate grounds it and holds it:

- `gather_context` gives repo-wide (single or multi-repo) candidates, similar existing features
  and graph neighbours, so the model reads that instead of exploring.
- The plan is submitted as structured data: the story as understood, then each change as `edit`
  (file, line range, before, after), `delete` (file, line range) or `add` (new file, content or
  outline), each with a reason.
- On submit the server **validates**: files exist, quoted "before" text matches the real lines,
  referenced symbols exist in the index, line ranges are valid, and each change cites evidence the
  tools actually returned. A failing plan goes back to the model with the exact mismatch before the
  developer ever sees it.
- Multi-repo: a change to a Kafka payload, REST contract, shared DTO or schema must list the
  affected producers, consumers and callers in other repos; a plan that misses one is flagged.
- The developer reviews a rendered plan ("file X, lines 40–52, A → B") and gives feedback.

**Why this does not hallucinate across rounds** (the failure seen with the earlier VS Code tool):

1. Every claim is verified against the index on every round.
2. Each revision is rebuilt from fresh facts (current file contents and hashes), not from chat memory.
3. Feedback becomes a **decision ledger** of short numbered decisions, not a growing transcript;
   round 8 costs about what round 2 does.
4. A revision **patches** specific plan items; the server holds the rest, so it cannot drift.
5. Each new version is checked against the ledger; touching something a decision ruled out is rejected.
6. Absence is explicit, so "not found" is never filled with an invention.

Approval is a real human action. As built (M5), the local page was dropped (local pages are blocked
on the team's machines) in favour of a form inside the Copilot chat: `plan_approve` sends an MCP
`elicitation/create` request, the client draws *Approve vN* / *Request changes*, and the answer goes
from the client to agentideate without passing through the model. A client without elicitation
falls back to its own confirmation of the (non-read-only) tool call, recorded as a weaker approval;
`--approval terminal` makes `agentideate approve` in a terminal the only way. The MCP server runs
requests on a worker thread so a tool can wait for the form while the reader keeps receiving.

### 3. Implement

Only after approval **and** an explicit Start, tied to one approved plan version:

1. Pre-flight: every target file's hash still matches plan time; otherwise nothing is written and a
   re-plan is requested.
2. Apply all-or-nothing: modify by replacing quoted text that must match exactly once; add refuses
   to overwrite; delete checks the graph for remaining references first.
3. Writes stay inside registered roots; path traversal is blocked; a snapshot allows full rollback.
4. Verify: re-read each change, compare the actual `git diff` with the plan (anything outside it is
   flagged), run an allowlisted `./gradlew compileJava` with a timeout, optionally the affected tests.
5. Report each change as applied, failed or rolled back; append to an audit log.

Copilot also has its own edit tools, which an MCP server cannot block; the after-the-fact diff
check catches edits made outside `implement_plan`.

### 4. Review

- Deterministic (no tokens): approved vs applied, unplanned changes, missing changes, manual drift
  after implementation, cross-repo impact re-check.
- Model, on a small pack (the diff and its graph neighbours): logic, edge cases, conventions, with
  findings required to cite real lines.
- Verdict: conforms / deviates (with list) / incomplete. Deviations go back to Workflow 2.

### 5. Unit tests

Changed methods and their existing tests come from the graph; the repo's conventions (JUnit 4/5,
Mockito, Testcontainers, EmbeddedKafka, naming) come from `build.gradle` and sibling tests. The
model writes tests from a compact pack; they go through the same approval gate; only those tests
run; failures come back compressed; a bounded fix loop (max 3) then an honest report. Optional
JaCoCo coverage of the changed lines.

### 6. Debug

Paste a failure or stack trace: it is compressed, frames are mapped to graph nodes, and recent
changes touching those frames (including the current session's) are shown. A fix is proposed as a
plan and goes through the same gate.

### Feedback loops

1. **Plan loop**: draft → validate → review → feedback → revise, until approval.
2. **Drift loop**: files changed after planning → implementation refuses → re-plan.
3. **Review loop**: deviations → back to planning as a new revision.
4. **Test loop**: write → run → compress failures → fix, at most 3 times → report.
5. **Debug loop**: a fix is always a plan.

## Distribution

1. CI builds `agentideate.jar` (plus SHA-256 and SBOM) on every push to `main` and attaches it to a
   GitHub Release. The jar is never committed.
2. The jar is uploaded to the office Artifactory; developers download it from there.
3. Each developer adds one entry to Copilot's `mcp.json` (see the README) and restarts IntelliJ.

## Milestones

| | Scope | Status |
|---|---|---|
| **M1** | Gradle skeleton, MCP stdio server with roots, sample microservices workspace, incremental Java index, graph (beans, routes, injection, interface dispatch, Feign hops), `repo_overview`, `locate`, `endpoint_flow`, savings footer and ledger | **done** |
| **M2** | Spring config reading (YAML subset, properties) with placeholder resolution; Kafka/RabbitMQ/JMS and Spring Cloud Stream (functional) producer-consumer map across services; build-file dependencies; entity → table/collection, repositories, migrations, datasources; reverse index; `messaging_map`, `data_map`, `impact_of`; Kafka hops in `endpoint_flow` | **done** |
| **M3** | Workflow 1 threads (relatedness by cited files and words, same branch, 6 h window; no repeats inside a thread; invalidation by file hash, including whole call trees; `thread_recall`), `slice_for_task`, `compress_log` (logs and JUnit XML) | **done** |
| **M4** | `bench` (tasks with expected facts vs the files that hold them; a lower-bound comparison, runnable on any repo), `report` (ledger summary), `setup` (prints the mcp.json entry, checks the machine), release workflow and `./gradlew release` (jar, SHA-256, CycloneDX SBOM), benchmark in CI | **done** |
| **M5** | Workflow 2: plan contract (edit by exact quote / add / delete, with reasons and evidence), validation against the code on every version (exact-once match, syntax after edit, package/type of new files, references of deleted files, evidence, decisions, cross-service effects), revision by patch, decision ledger, approval as an in-chat form (MCP elicitation) with Copilot's tool confirmation as fallback and a terminal-only mode; `plan_submit`, `plan_show`, `plan_approve`; CLI `plans`, `plan`, `approve`, `call` | **done** |
| M6 | Workflow 3: implement, verify, rollback, audit | |
| M7 | Workflows 4 and 5: review, unit tests | |
| M8 | Workflow 6: debug | |
| later | OpenRewrite codemods for mechanical changes | |
