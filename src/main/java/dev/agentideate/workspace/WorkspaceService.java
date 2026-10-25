package dev.agentideate.workspace;

import dev.agentideate.AppHome;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.index.WorkspaceIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which repositories a request is about and hands back a fresh graph for them.
 *
 * <p>Roots come from, in order: the tool call's {@code repo} argument, {@code --repo} options on the
 * server command line, the {@code AGENTIDEATE_REPOS} variable, the roots the MCP client reports for
 * the open project, and finally the working directory when it looks like a project.
 */
public final class WorkspaceService {

    public record Snapshot(Workspace workspace, CodeGraph graph, WorkspaceIndex.Stats stats, Path stateDir) {
    }

    /** No repository could be determined; the message says how to name one. */
    public static final class NoWorkspaceException extends IOException {
        public NoWorkspaceException(String message) {
            super(message);
        }
    }

    private record Entry(WorkspaceIndex index, CodeGraph graph) {
    }

    private final AppHome home;
    private final List<Path> configuredRoots;
    private final Path workingDir;
    private volatile List<Path> clientRoots = List.of();
    private final Map<String, Entry> entries = new HashMap<>();

    public WorkspaceService(AppHome home, List<Path> configuredRoots, Path workingDir) {
        this.home = home;
        this.configuredRoots = List.copyOf(configuredRoots);
        this.workingDir = workingDir;
    }

    public AppHome home() {
        return home;
    }

    /** Roots reported by the MCP client ({@code roots/list}); replaces any earlier report. */
    public void setClientRoots(List<Path> roots) {
        this.clientRoots = List.copyOf(roots);
    }

    public List<Path> clientRoots() {
        return clientRoots;
    }

    public synchronized Snapshot snapshot(String repoArg) throws IOException {
        Workspace ws = Workspace.of(chooseRoots(repoArg));
        if (!ws.allRootsExist()) {
            List<String> missing = new ArrayList<>();
            ws.roots().stream().filter(r -> !Files.isDirectory(r.path())).forEach(r -> missing.add(r.path().toString()));
            throw new NoWorkspaceException("Repository folder not found: " + String.join(", ", missing));
        }
        Path stateDir = home.workspaceDir(ws.id());
        Entry entry = entries.get(ws.id());
        if (entry == null) {
            entry = new Entry(new WorkspaceIndex(ws, stateDir), null);
        }
        WorkspaceIndex.Stats stats = entry.index().refresh();
        CodeGraph graph = entry.graph();
        if (graph == null || stats.changed()) {
            graph = new CodeGraph(ws, entry.index().files());
        }
        entries.put(ws.id(), new Entry(entry.index(), graph));
        return new Snapshot(ws, graph, stats, stateDir);
    }

    List<Path> chooseRoots(String repoArg) throws NoWorkspaceException {
        if (repoArg != null && !repoArg.isBlank()) {
            return split(repoArg);
        }
        if (!configuredRoots.isEmpty()) {
            return configuredRoots;
        }
        String env = System.getenv("AGENTIDEATE_REPOS");
        if (env != null && !env.isBlank()) {
            return split(env);
        }
        if (!clientRoots.isEmpty()) {
            return clientRoots;
        }
        if (workingDir != null && looksLikeProject(workingDir)) {
            return List.of(workingDir);
        }
        throw new NoWorkspaceException(
                "No repository to work on. Pass the project folder as the 'repo' argument "
                        + "(several folders separated by ','), or add \"--repo\", \"<folder>\" to the "
                        + "agentideate entry in mcp.json.");
    }

    private static List<Path> split(String value) {
        List<Path> out = new ArrayList<>();
        for (String part : value.split("[,;]")) {
            if (!part.isBlank()) {
                out.add(Path.of(part.trim()));
            }
        }
        return out;
    }

    static boolean looksLikeProject(Path dir) {
        for (String marker : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                "pom.xml", ".git")) {
            if (Files.exists(dir.resolve(marker))) {
                return true;
            }
        }
        return false;
    }
}
