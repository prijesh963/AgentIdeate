package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools, and the one path every call takes: resolve the workspace, refresh the index, run the
 * tool under a size budget, append the savings footer, write the ledger. MCP and the CLI both call
 * {@link #call}, so they cannot answer the same question differently.
 */
public final class ToolRegistry {

    public record Outcome(String text, boolean error) {
    }

    /** Hard cap on one answer (~2k tokens). An answer that needs more says so and asks for a narrower query. */
    static final int BUDGET_CHARS = 8_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkspaceService workspaces;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(WorkspaceService workspaces, List<Tool> tools) {
        this.workspaces = workspaces;
        tools.forEach(t -> this.tools.put(t.name(), t));
    }

    public static ToolRegistry standard(WorkspaceService workspaces) {
        return new ToolRegistry(workspaces, List.of(new RepoOverviewTool(), new LocateTool(), new EndpointFlowTool(),
                new MessagingMapTool(), new DataMapTool(), new ImpactTool()));
    }

    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    /** The tool's schema with the optional {@code repo} argument every tool accepts. */
    public ObjectNode schema(Tool tool) {
        ObjectNode schema = tool.inputSchema().deepCopy();
        ObjectNode props = schema.has("properties") ? (ObjectNode) schema.get("properties") : schema.putObject("properties");
        props.putObject("repo").put("type", "string").put("description",
                "Project folder(s), comma-separated. Omit to use the open project.");
        return schema;
    }

    public Outcome call(String name, JsonNode arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return new Outcome("Unknown tool '" + name + "'. Available: " + String.join(", ", tools.keySet()), true);
        }
        long start = System.nanoTime();
        ToolArgs args = new ToolArgs(arguments);
        WorkspaceService.Snapshot snapshot;
        try {
            snapshot = workspaces.snapshot(args.string("repo"));
        } catch (IOException e) {
            return new Outcome(e.getMessage(), true);
        }
        Answer out = new Answer(snapshot.workspace(), BUDGET_CHARS);
        try {
            tool.run(args, snapshot, snapshot.graph(), out);
        } catch (ToolException e) {
            return new Outcome(e.getMessage(), true);
        } catch (RuntimeException e) {
            return new Outcome("agentideate failed on '" + name + "': " + e, true);
        }
        String body = out.body();
        long referencedBytes = 0;
        for (SourceFile f : out.referenced().values()) {
            referencedBytes += f.size();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        String footer = footer(out.referenced().size(), referencedBytes, body.length(), snapshot, millis);
        Ledger.record(snapshot.stateDir(), name, millis, body.length(), out.referenced().size(), referencedBytes);
        return new Outcome(body + footer, false);
    }

    /**
     * States what the answer stood in for. Token figures use the common ~4 characters per token
     * estimate; the referenced-files figure is what opening every cited file would have cost.
     */
    static String footer(int files, long referencedBytes, int returnedChars, WorkspaceService.Snapshot s, long millis) {
        var st = s.stats();
        String index = "index " + st.javaFiles() + " Java files"
                + (st.reparsed() > 0 ? ", " + st.reparsed() + " files (re)indexed now" : "")
                + (st.parseErrors() > 0 ? ", " + st.parseErrors() + " unparseable" : "")
                + ", " + millis + " ms";
        if (files == 0) {
            return "-- agentideate: " + index + "\n";
        }
        return "-- agentideate: from " + files + " source file" + (files == 1 ? "" : "s")
                + " (~" + tokens(referencedBytes) + " tokens to open) -> ~" + tokens(returnedChars)
                + " tokens here; " + index + "\n";
    }

    static String tokens(long chars) {
        long t = Math.max(1, chars / 4);
        return t >= 1000 ? String.format("%.1fk", t / 1000.0) : Long.toString(t);
    }

    static ObjectNode objectSchema() {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }
}
