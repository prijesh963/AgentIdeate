package dev.agentideate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.bench.Benchmark;
import dev.agentideate.implement.TerminalImplement;
import dev.agentideate.mcp.McpServer;
import dev.agentideate.plan.TerminalApproval;
import dev.agentideate.report.LedgerReport;
import dev.agentideate.tools.ToolRegistry;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. With no command it serves MCP on stdio, which is how Copilot starts it from
 * {@code mcp.json}. The other commands run the same tools from a terminal.
 */
public final class Main {

    static final String USAGE = """
            agentideate %s - local Java/Spring repo intelligence for AI coding agents

            Usage:
              java -jar agentideate.jar [--repo <folder>]...       serve MCP on stdio (what mcp.json runs)
              java -jar agentideate.jar overview [--repo <folder>]...
              java -jar agentideate.jar locate <query> [--repo <folder>]...
              java -jar agentideate.jar flow <path> [GET|POST|...] [--repo <folder>]...
              java -jar agentideate.jar messaging [channel] [--repo <folder>]...
              java -jar agentideate.jar data [name] [--repo <folder>]...
              java -jar agentideate.jar impact <Class | Class#method | topic | table> [--repo <folder>]...
              java -jar agentideate.jar slice <task words...> [--repo <folder>]...
              java -jar agentideate.jar log [log file] [--repo <folder>]...     (no file: JUnit XML reports)
              java -jar agentideate.jar recall [topic | thread id] [--repo <folder>]...
              java -jar agentideate.jar bench <tasks.json> [--repo <folder>]...  (exit 1 when an answer misses a fact)
              java -jar agentideate.jar report [--days N] [--all] [--repo <folder>]...
              java -jar agentideate.jar setup                                    print the mcp.json entry and check this machine
              java -jar agentideate.jar plans | plan <id> [version]               list or show plans
              java -jar agentideate.jar approve <id> [version]                     approve a plan here, in the terminal
              java -jar agentideate.jar implement <id> [--no-compile]              write an approved plan (asks first)
              java -jar agentideate.jar rollback <run | plan id>                   undo an implementation run
              java -jar agentideate.jar call <tool> '<json arguments>'             run any tool (scripting, debugging)

            Serving MCP, --approval terminal (or AGENTIDEATE_APPROVAL=terminal) makes the terminal the only place
            plans are approved; by default the developer approves in the Copilot chat.
              java -jar agentideate.jar --version | help

            Without --repo the open project (from Copilot) or the current folder is used.
            State lives in ~/.agentideate (override with AGENTIDEATE_HOME); nothing is written to your project.
            """;

    public static void main(String[] argv) throws IOException {
        System.exit(run(argv, System.in, System.out, System.err, AppHome.resolve()));
    }

    static int run(String[] argv, InputStream stdin, PrintStream stdout, PrintStream stderr, AppHome home)
            throws IOException {
        List<Path> repos = new ArrayList<>();
        List<String> positional = new ArrayList<>();
        int days = 7;
        boolean allWorkspaces = false;
        boolean noCompile = false;
        String approval = System.getenv("AGENTIDEATE_APPROVAL");
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("--days") && i + 1 < argv.length) {
                try {
                    days = Integer.parseInt(argv[++i]);
                } catch (NumberFormatException e) {
                    stderr.println("--days needs a number");
                    return 2;
                }
            } else if (a.equals("--all")) {
                allWorkspaces = true;
            } else if (a.equals("--no-compile")) {
                noCompile = true;
            } else if (a.equals("--approval") && i + 1 < argv.length) {
                approval = argv[++i];
            } else if (a.equals("--repo") || a.equals("-r")) {
                if (i + 1 >= argv.length) {
                    stderr.println("--repo needs a folder");
                    return 2;
                }
                repos.add(Path.of(argv[++i]));
            } else if (a.startsWith("--repo=")) {
                repos.add(Path.of(a.substring("--repo=".length())));
            } else {
                positional.add(a);
            }
        }
        WorkspaceService workspaces = new WorkspaceService(home, repos, Path.of("").toAbsolutePath());
        ToolRegistry registry = ToolRegistry.standard(workspaces);
        if (approval != null) {
            if (!approval.equals("chat") && !approval.equals("terminal")) {
                stderr.println("--approval must be chat or terminal");
                return 2;
            }
            registry.approvalMode(approval);
        }

        String command = positional.isEmpty() ? "serve" : positional.get(0);
        try {
            return dispatch(command, positional, repos, days, allWorkspaces, noCompile, workspaces, registry, home, stdin, stdout, stderr);
        } catch (WorkspaceService.NoWorkspaceException e) {
            stderr.println(e.getMessage());
            return 1;
        }
    }

    private static int dispatch(String command, List<String> positional, List<Path> repos, int days, boolean allWorkspaces,
                                boolean noCompile, WorkspaceService workspaces, ToolRegistry registry, AppHome home, InputStream stdin,
                                PrintStream stdout, PrintStream stderr) throws IOException {
        ObjectNode args = new ObjectMapper().createObjectNode();
        switch (command) {
            case "serve" -> {
                new McpServer(registry, workspaces, stdin, stdout, stderr).run();
                return 0;
            }
            case "--version", "version" -> {
                stdout.println(Version.current());
                return 0;
            }
            case "help", "--help", "-h" -> {
                stdout.print(USAGE.formatted(Version.current()));
                return 0;
            }
            case "overview" -> {
                return print(registry.call("repo_overview", args), stdout, stderr);
            }
            case "locate" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: locate <query>");
                    return 2;
                }
                args.put("query", String.join(" ", positional.subList(1, positional.size())));
                return print(registry.call("locate", args), stdout, stderr);
            }
            case "flow" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: flow <path> [method]");
                    return 2;
                }
                args.put("path", positional.get(1));
                if (positional.size() > 2) {
                    args.put("method", positional.get(2));
                }
                return print(registry.call("endpoint_flow", args), stdout, stderr);
            }
            case "messaging" -> {
                if (positional.size() > 1) {
                    args.put("channel", positional.get(1));
                }
                return print(registry.call("messaging_map", args), stdout, stderr);
            }
            case "data" -> {
                if (positional.size() > 1) {
                    args.put("name", positional.get(1));
                }
                return print(registry.call("data_map", args), stdout, stderr);
            }
            case "impact" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: impact <Class | Class#method | topic | table>");
                    return 2;
                }
                args.put("target", positional.get(1));
                return print(registry.call("impact_of", args), stdout, stderr);
            }
            case "slice" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: slice <task words...>");
                    return 2;
                }
                args.put("task", String.join(" ", positional.subList(1, positional.size())));
                return print(registry.call("slice_for_task", args), stdout, stderr);
            }
            case "log" -> {
                if (positional.size() > 1) {
                    args.put("path", positional.get(1));
                }
                return print(registry.call("compress_log", args), stdout, stderr);
            }
            case "recall" -> {
                if (positional.size() > 1) {
                    args.put("topic", String.join(" ", positional.subList(1, positional.size())));
                }
                return print(registry.call("thread_recall", args), stdout, stderr);
            }
            case "bench" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: bench <tasks.json> [--repo <folder>]");
                    return 2;
                }
                List<Benchmark.Task> tasks;
                try {
                    tasks = Benchmark.load(Path.of(positional.get(1)));
                } catch (IOException e) {
                    stderr.println("Cannot read tasks from " + positional.get(1) + ": " + e.getMessage());
                    return 1;
                }
                var ws = workspaces.workspaceFor(null);
                var results = Benchmark.run(registry, ws, tasks);
                String label = String.join(", ", ws.roots().stream().map(r -> r.name()).toList());
                stdout.print(Benchmark.render(results, label, registry.toolsList().toString().length()));
                return results.stream().allMatch(Benchmark.Result::passed) ? 0 : 1;
            }
            case "report" -> {
                long since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
                if (allWorkspaces) {
                    stdout.print(LedgerReport.render(LedgerReport.allWorkspaces(home.dir()), since,
                            "all workspaces, last " + days + " days"));
                } else {
                    var ws = workspaces.workspaceFor(null);
                    stdout.print(LedgerReport.render(List.of(home.workspaceDir(ws.id())), since,
                            String.join(", ", ws.roots().stream().map(r -> r.name()).toList()) + ", last " + days + " days"));
                }
                return 0;
            }
            case "setup" -> {
                return Setup.run(home, stdout);
            }
            case "plans" -> {
                return print(registry.call("plan_show", args), stdout, stderr);
            }
            case "plan" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: plan <id> [version]");
                    return 2;
                }
                args.put("plan", positional.get(1));
                if (positional.size() > 2) {
                    args.put("version", positional.get(2));
                }
                return print(registry.call("plan_show", args), stdout, stderr);
            }
            case "approve" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: approve <id> [version]");
                    return 2;
                }
                Integer version = null;
                if (positional.size() > 2) {
                    try {
                        version = Integer.parseInt(positional.get(2));
                    } catch (NumberFormatException e) {
                        stderr.println("version must be a number");
                        return 2;
                    }
                }
                return TerminalApproval.run(workspaces.snapshot(null), positional.get(1), version, stdin, stdout, stderr,
                        System.currentTimeMillis());
            }
            case "implement" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: implement <plan id> [--no-compile]");
                    return 2;
                }
                return TerminalImplement.implement(workspaces.snapshot(null), registry.runner(), positional.get(1),
                        registry.compileEnabled() && !noCompile, stdin, stdout, stderr, System.currentTimeMillis());
            }
            case "rollback" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: rollback <run id | plan id>");
                    return 2;
                }
                return TerminalImplement.rollback(workspaces.snapshot(null), registry.runner(), positional.get(1), stdout, stderr,
                        System.currentTimeMillis());
            }
            case "call" -> {
                if (positional.size() < 2) {
                    stderr.println("usage: call <tool> '<json arguments>'");
                    return 2;
                }
                JsonNode callArgs;
                try {
                    callArgs = positional.size() > 2 ? new ObjectMapper().readTree(positional.get(2)) : args;
                } catch (IOException e) {
                    stderr.println("arguments must be a JSON object: " + e.getMessage());
                    return 2;
                }
                return print(registry.call(positional.get(1), callArgs), stdout, stderr);
            }
            default -> {
                stderr.println("Unknown command '" + command + "'.");
                stderr.print(USAGE.formatted(Version.current()));
                return 2;
            }
        }
    }

    private static int print(ToolRegistry.Outcome outcome, PrintStream stdout, PrintStream stderr) {
        (outcome.error() ? stderr : stdout).print(outcome.text().endsWith("\n") ? outcome.text() : outcome.text() + "\n");
        return outcome.error() ? 1 : 0;
    }

    static {
        // Windows consoles default to a legacy code page; answers contain '·' and arrows.
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
    }
}
