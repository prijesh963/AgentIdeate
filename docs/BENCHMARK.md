# Measuring agentideate

Two instruments, both local and both reproducible:

- `bench` runs a set of tasks and compares each answer with the files an agent would otherwise read;
- `report` summarizes the ledger of real tool calls made by Copilot on your machine.

## The benchmark on the sample

`java -jar agentideate.jar bench benchmark/shop.json --repo samples/shop` (CI runs this on every push):

```text
agentideate benchmark: 11 tasks on shop

 #   question                                             tool             answer    files to read  saved  facts
 1   Which services exist and what do they expose?        repo_overview       619        1.9k (13)    67%  5/5
 2   How does POST /api/orders work end to end?           endpoint_flow       630        2.3k (12)    72%  6/6
 3   Who consumes order.created?                          messaging_map       165          930 (6)    82%  3/3
 4   Where is stock reserved?                             locate              361          502 (2)    28%  2/2
 5   Is invoice approval already implemented?             locate               88                -      -  1/1
 6   Which tables does order-service use, and which mi... data_map            129          375 (5)    66%  4/4
 7   What does changing OrderEntity touch?                impact_of           357         1.6k (8)    78%  5/5
 8   What reaches InventoryService#reserve?               impact_of           180          647 (3)    72%  2/2
 9   Context to add a discount when placing an order      slice_for_task      978         1.4k (6)    30%  4/4
 10  Which topics and queues exist, and who produces a... messaging_map       508         1.3k (9)    62%  5/5
 11  Why did order-service fail to start?                 compress_log        217          462 (1)    53%  3/3

Total (tasks with files to read): answers 4.1k tokens vs 11.4k tokens of files: 64% fewer. Facts found: 40/40.
Tool calls: 10 (one per task) instead of at least 65 file reads. Files here average 175 tokens; larger files mean larger savings.
Fixed cost: agentideate's tool definitions add ~1.3k tokens to every agent request.
"files to read" counts only the files that hold the answer, each read once, with no searching and no
wrong turns: the least an agent without agentideate must read. Tokens are estimated at 4 characters.
```

### How to read it

- **answer** is everything the agent receives from agentideate for the task, footer included.
- **files to read** is the size of the files that hold the answer, each read once. It assumes the
  agent already knows which files those are, never searches, never opens a wrong file, and never
  re-reads one. Real agents do all of those things, so this is the *least* an agent without
  agentideate reads, and the saved column is the *least* saving.
- **facts** checks that each answer contains what it must (a route, a consumer, a table). A missing
  fact fails the task and makes `bench` exit with 1, so the benchmark also guards correctness.
- **The sample is small.** Its files average under 200 tokens; real service classes are often
  1,000 to 5,000. agentideate's answers grow with the number of things found, not with file size,
  so on a real repository the gap is expected to be wider. That is an expectation, not a
  measurement: measure it on your own code (below).
- **The fixed cost** is the tool definitions Copilot sends with every agent request (about 1.3k
  tokens). It is paid per request, whether or not a tool is called.
- **Calls:** one tool call per task, against at least one file read per file. Each agent step is a
  model request, and each request re-sends the conversation so far, so fewer steps also means less
  context sent again.

## Run it on your own repository

Write a task file for questions your team actually asks, with the facts a correct answer must
contain and the files a developer would open to answer it:

```json
{
  "tasks": [
    {
      "id": "create-invoice-flow",
      "question": "How does POST /api/invoices work?",
      "tool": "endpoint_flow",
      "args": {"path": "/api/invoices", "method": "POST"},
      "expect": ["InvoiceServiceImpl#create", "kafka invoice.created"],
      "files": ["invoice-service/src/main/java/com/acme/invoice/web/InvoiceController.java",
                "invoice-service/src/main/java/com/acme/invoice/service/InvoiceServiceImpl.java"]
    }
  ]
}
```

```bash
java -jar agentideate.jar bench my-tasks.json --repo /work/invoice-service --repo /work/billing-service
```

`files` are relative to a repository root (prefix with the root's folder name when you pass several
repos). A file that does not exist fails the task, so the comparison cannot be inflated. `tool` is
any agentideate tool and `args` its arguments, as in the tool table in the README.

## What Copilot actually used: the ledger

Every tool call appends a line to `~/.agentideate/workspaces/<id>/ledger.jsonl`: tool, time, answer
size, the size of the files the answer cites, and what a thread did not repeat. Summarize it:

```bash
java -jar agentideate.jar report --repo /work/invoice-service     # this workspace, last 7 days
java -jar agentideate.jar report --all --days 30                  # every workspace on this machine
```

"Estimated reading avoided" is the cited files minus the answers, plus what threads did not repeat.
It assumes the agent would have opened every cited file; it might have opened fewer, or more. To
see the effect on your Copilot credits, compare a week of agent work with the agentideate entry in
`mcp.json` against a week without it.
