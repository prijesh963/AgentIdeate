package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.WorkspaceService;

import dev.agentideate.session.ThreadStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
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
    private final Map<Path, ThreadStore> threadStores = new HashMap<>();
    private Clock clock = Clock.systemUTC();

    public ToolRegistry(WorkspaceService workspaces, List<Tool> tools) {
        this.workspaces = workspaces;
        tools.forEach(t -> this.tools.put(t.name(), t));
    }

    public static ToolRegistry standard(WorkspaceService workspaces) {
        ToolRegistry r = new ToolRegistry(workspaces, List.of(new RepoOverviewTool(), new LocateTool(), new EndpointFlowTool(),
                new MessagingMapTool(), new DataMapTool(), new ImpactTool(), new SliceTool(), new CompressLogTool()));
        r.add(new ThreadRecallTool(r));
        return r;
    }

    long now() {
        return clock.millis();
    }

    private void add(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** For tests: a controllable clock decides thread idle windows. */
    void clock(Clock clock) {
        this.clock = clock;
    }

    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    /** The tool's schema with the optional {@code repo} argument every tool accepts. */
    public ObjectNode schema(Tool tool) {
        ObjectNode schema = tool.inputSchema().deepCopy();
        ObjectNode props = schema.has("properties") ? (ObjectNode) schema.get("properties") : schema.putObject("properties");
        // These two are repeated on every tool and sent with every request: keep them terse.
        props.putObject("repo").put("type", "string").put("description", "Project folder(s); omit for the open one");
        if (tool.threaded()) {
            props.putObject("thread").put("type", "string").put("description", "Omit, or 'new' to repeat in full");
        }
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
        Answer out;
        ThreadStore.Choice choice = null;
        try {
            if (tool.threaded()) {
                // Pass 1 finds the files the answer cites, which decides the thread; pass 2 renders
                // the answer against that thread. Tools are deterministic and fast, so this is cheap.
                Answer probe = new Answer(snapshot.workspace(), tool.budgetChars());
                tool.run(args, snapshot, snapshot.graph(), probe);
                Map<String, String> hashes = hashes(snapshot);
                choice = threads(snapshot).choose(args.string("thread"), probe.referenced().keySet(),
                        questionWords(arguments), snapshot.workspace().branch(), clock.millis());
                choice.thread().invalidate(hashes);
                out = new Answer(snapshot.workspace(), tool.budgetChars(), choice.continued() ? choice.thread() : null,
                        snapshot.graph().fileNameCounts());
            } else {
                out = new Answer(snapshot.workspace(), tool.budgetChars());
            }
            tool.run(args, snapshot, snapshot.graph(), out);
        } catch (ToolException e) {
            return new Outcome(e.getMessage(), true);
        } catch (RuntimeException e) {
            return new Outcome("agentideate failed on '" + name + "': " + e, true);
        }
        String body = out.body();
        long referencedBytes = out.inputBytes();
        for (SourceFile f : out.referenced().values()) {
            referencedBytes += f.size();
        }
        String threadNote = "";
        if (choice != null) {
            try {
                recordInThread(choice, name, arguments, out, snapshot);
                threadNote = threadNote(choice, out);
            } catch (IOException e) {
                threadNote = "thread not saved (" + e.getMessage() + ")";
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        String footer = footer(out.referenced().size(), referencedBytes, body.length(), snapshot, millis, threadNote,
                out.inputLabel());
        Ledger.record(snapshot.stateDir(), name, millis, body.length(), out.referenced().size(), referencedBytes,
                choice == null ? null : choice.thread().id, choice != null && choice.continued(), out.omittedChars());
        return new Outcome(body + footer, false);
    }

    private void recordInThread(ThreadStore.Choice choice, String tool, JsonNode arguments, Answer out,
                                WorkspaceService.Snapshot snapshot) throws IOException {
        Map<String, String> cited = new LinkedHashMap<>();
        for (SourceFile f : out.referenced().values()) {
            cited.put(f.key(), f.sha256());
        }
        threads(snapshot).record(choice.thread(), question(tool, arguments), questionWords(arguments), cited,
                out.newLines(), out.newNodes(), out.omittedChars(), clock.millis());
    }

    private static String threadNote(ThreadStore.Choice choice, Answer out) {
        var t = choice.thread();
        String note = "thread " + t.id + (choice.continued() ? " continued (" + t.calls + " questions so far)" : " new");
        if (out.omittedLines() > 0) {
            note += ", " + out.omittedLines() + " lines not repeated";
        }
        return note;
    }

    /** The thread store of the snapshot's workspace, kept for the life of the process. */
    public synchronized ThreadStore threads(WorkspaceService.Snapshot snapshot) {
        return threadStores.computeIfAbsent(snapshot.stateDir(), ThreadStore::new);
    }

    private static Map<String, String> hashes(WorkspaceService.Snapshot snapshot) {
        Map<String, String> out = new HashMap<>();
        for (SourceFile f : snapshot.graph().files()) {
            out.put(f.key(), f.sha256());
        }
        return out;
    }

    /** {@code tool arg1 arg2}: how a question is shown in a thread. */
    static String question(String tool, JsonNode arguments) {
        StringBuilder sb = new StringBuilder(tool);
        if (arguments != null) {
            arguments.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("repo") && !e.getKey().equals("thread")) {
                    sb.append(' ').append(e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString());
                }
            });
        }
        return sb.toString();
    }

    /** The words of a question's arguments, for matching it to a thread. */
    static Set<String> questionWords(JsonNode arguments) {
        Set<String> words = new LinkedHashSet<>();
        if (arguments != null) {
            arguments.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("repo") && !e.getKey().equals("thread") && e.getValue().isTextual()) {
                    words.addAll(LocateTool.queryTokens(e.getValue().asText()));
                }
            });
        }
        return words;
    }

    /**
     * States what the answer stood in for. Token figures use the common ~4 characters per token
     * estimate; the referenced-files figure is what opening every cited file would have cost.
     */
    static String footer(int files, long referencedBytes, int returnedChars, WorkspaceService.Snapshot s, long millis,
                         String threadNote, String inputLabel) {
        var st = s.stats();
        String index = "index " + st.javaFiles() + " Java files"
                + (st.reparsed() > 0 ? ", " + st.reparsed() + " files (re)indexed now" : "")
                + (st.parseErrors() > 0 ? ", " + st.parseErrors() + " unparseable" : "")
                + ", " + millis + " ms";
        String thread = threadNote == null || threadNote.isEmpty() ? "" : threadNote + "; ";
        if (referencedBytes == 0) {
            return "-- agentideate: " + thread + index + "\n";
        }
        String source = inputLabel != null
                ? inputLabel + (files > 0 ? " and " + files + " source file" + (files == 1 ? "" : "s") : "")
                : files + " source file" + (files == 1 ? "" : "s");
        String verb = inputLabel != null ? " to read" : " to open";
        return "-- agentideate: " + thread + "from " + source + " (~" + tokens(referencedBytes) + " tokens" + verb
                + ") -> ~" + tokens(returnedChars) + " tokens here; " + index + "\n";
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
