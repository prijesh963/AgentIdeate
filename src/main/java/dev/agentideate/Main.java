package dev.agentideate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.mcp.McpServer;
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
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("--repo") || a.equals("-r")) {
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

        String command = positional.isEmpty() ? "serve" : positional.get(0);
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
