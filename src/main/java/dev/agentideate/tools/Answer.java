package dev.agentideate.tools;

import dev.agentideate.index.SourceFile;
import dev.agentideate.session.ConversationThread;
import dev.agentideate.workspace.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a tool's text answer under a size budget, and records which indexed files it drew on so
 * the footer can say what reading those files directly would have cost.
 *
 * <p>Plain text, not JSON: the same facts cost noticeably fewer tokens without keys and quotes.
 *
 * <p>Inside a continued conversation thread an answer does not repeat itself: a file already cited
 * in the thread is shown by its file name (when that name is unique in the workspace), a list line
 * already given is left out and counted, and a flow node already expanded is only named. The agent
 * still has the earlier answers in its context, so nothing is lost but the repetition.
 */
public final class Answer {

    private record PendingRef(String fileKey, String full, String shown) {
    }

    private final Workspace workspace;
    private final int budgetChars;
    private final ConversationThread thread;
    private final Map<String, Integer> fileNameCounts;
    private final StringBuilder text = new StringBuilder();
    private final Map<String, SourceFile> referenced = new LinkedHashMap<>();
    private final List<PendingRef> pending = new ArrayList<>();
    private final Map<String, List<String>> newLines = new LinkedHashMap<>();
    private final Map<String, List<String>> newNodes = new LinkedHashMap<>();
    private int droppedLines;
    private int omittedLines;
    private int collapsedNodes;
    private long omittedChars;
    private long inputBytes;
    private String inputLabel;

    public Answer(Workspace workspace, int budgetChars) {
        this(workspace, budgetChars, null, Map.of());
    }

    /**
     * @param thread         the conversation thread this answer continues, or {@code null}
     * @param fileNameCounts how many workspace files share each file name, to know when a bare file
     *                       name is unambiguous
     */
    public Answer(Workspace workspace, int budgetChars, ConversationThread thread, Map<String, Integer> fileNameCounts) {
        this.workspace = workspace;
        this.budgetChars = budgetChars;
        this.thread = thread;
        this.fileNameCounts = fileNameCounts;
    }

    public Answer line(String s) {
        pending.clear();
        if (droppedLines > 0 || text.length() + s.length() + 1 > budgetChars) {
            droppedLines++;
            return this;
        }
        text.append(s).append('\n');
        return this;
    }

    public Answer blank() {
        return line("");
    }

    /**
     * A self-contained line of a list (a search hit, a caller, a consumer). Left out when this
     * thread already gave the same line, unless a file it cites has changed since.
     *
     * @return whether the line was shown
     */
    public boolean fact(String s) {
        String key = s;
        List<String> deps = new ArrayList<>();
        for (PendingRef r : pending) {
            if (!r.shown().equals(r.full())) {
                key = key.replace(r.shown(), r.full());
            }
            deps.add(r.fileKey());
        }
        if (thread != null && thread.lines.containsKey(key)) {
            omittedLines++;
            omittedChars += s.length() + 1;
            pending.clear();
            return false;
        }
        newLines.put(key, deps);
        line(s);
        return true;
    }

    /** Whether this thread already expanded the flow node {@code key}. */
    public boolean shownEarlier(String nodeKey) {
        boolean earlier = thread != null && thread.nodes.containsKey(nodeKey);
        if (earlier) {
            collapsedNodes++;
        }
        return earlier;
    }

    /** Records that the flow node {@code key} was expanded here, over a call tree spanning {@code files}. */
    public void expanded(String nodeKey, Set<String> files) {
        newNodes.put(nodeKey, List.copyOf(files));
    }

    /** The files of the call tree a node expanded earlier in this thread. */
    public List<String> earlierFiles(String nodeKey) {
        return thread == null ? List.of() : thread.nodes.getOrDefault(nodeKey, List.of());
    }

    /** The file's display path, recording it as a source of this answer. */
    public String ref(SourceFile f) {
        referenced.putIfAbsent(f.key(), f);
        String full = workspace.display(f);
        String shown = full;
        if (thread != null && thread.files.containsKey(f.key())) {
            String name = f.path().substring(f.path().lastIndexOf('/') + 1);
            if (fileNameCounts.getOrDefault(name, 0) == 1) {
                shown = name;
            }
        }
        pending.add(new PendingRef(f.key(), full, shown));
        return shown;
    }

    public String ref(SourceFile f, int line) {
        String shown = ref(f);
        PendingRef last = pending.remove(pending.size() - 1);
        pending.add(new PendingRef(last.fileKey(), last.full() + ":" + line, shown + ":" + line));
        return shown + ":" + line;
    }

    /** Counts input the answer stands in for that is not an indexed file (a pasted log, a report). */
    public void input(long bytes, String label) {
        inputBytes += bytes;
        inputLabel = label;
    }

    public Map<String, SourceFile> referenced() {
        return referenced;
    }

    public int droppedLines() {
        return droppedLines;
    }

    public int omittedLines() {
        return omittedLines;
    }

    public long omittedChars() {
        return omittedChars;
    }

    public long inputBytes() {
        return inputBytes;
    }

    public String inputLabel() {
        return inputLabel;
    }

    Map<String, List<String>> newLines() {
        return newLines;
    }

    Map<String, List<String>> newNodes() {
        return newNodes;
    }

    public String body() {
        String b = text.toString();
        if (omittedLines > 0 || collapsedNodes > 0) {
            List<String> parts = new ArrayList<>();
            if (omittedLines > 0) {
                parts.add(omittedLines + " line" + (omittedLines == 1 ? "" : "s") + " already given earlier in this thread");
            }
            if (collapsedNodes > 0) {
                parts.add(collapsedNodes + " call tree" + (collapsedNodes == 1 ? "" : "s") + " expanded earlier");
            }
            b += "[" + String.join(", ", parts) + ": not repeated. If they are not in your context, ask again with thread: \"new\".]\n";
        }
        if (droppedLines > 0) {
            b += "[+" + droppedLines + " more lines not shown - narrow the query]\n";
        }
        return b;
    }
}
