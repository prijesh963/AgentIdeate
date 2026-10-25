package dev.agentideate.tools;

import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.Workspace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a tool's text answer under a size budget, and records which indexed files it drew on so
 * the footer can say what reading those files directly would have cost.
 *
 * <p>Plain text, not JSON: the same facts cost noticeably fewer tokens without keys and quotes.
 */
public final class Answer {

    private final Workspace workspace;
    private final int budgetChars;
    private final StringBuilder text = new StringBuilder();
    private final Map<String, SourceFile> referenced = new LinkedHashMap<>();
    private int droppedLines;

    public Answer(Workspace workspace, int budgetChars) {
        this.workspace = workspace;
        this.budgetChars = budgetChars;
    }

    public Answer line(String s) {
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

    /** The file's display path, recording it as a source of this answer. */
    public String ref(SourceFile f) {
        referenced.putIfAbsent(f.key(), f);
        return workspace.display(f);
    }

    public String ref(SourceFile f, int line) {
        return ref(f) + ":" + line;
    }

    public Map<String, SourceFile> referenced() {
        return referenced;
    }

    public int droppedLines() {
        return droppedLines;
    }

    public String body() {
        String b = text.toString();
        if (droppedLines > 0) {
            b += "[+" + droppedLines + " more lines not shown - narrow the query]\n";
        }
        return b;
    }
}
