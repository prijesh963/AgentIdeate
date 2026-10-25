package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.index.SourceFile;
import dev.agentideate.session.ConversationThread;
import dev.agentideate.session.ThreadStore;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings back an earlier conversation thread: what was asked and which files were central, so a
 * new chat (or one whose history was trimmed) picks up a topic without re-exploring. Recalling a
 * thread makes it the active one and resets what it counts as "already given", since the chat
 * that recalls it may not have the earlier answers.
 */
final class ThreadRecallTool implements Tool {

    static final int MAX_FILES = 20;
    static final int MAX_LISTED = 10;

    private final ToolRegistry registry;

    ThreadRecallTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "thread_recall";
    }

    @Override
    public String description() {
        return "Recall an earlier topic (questions asked, central files) to continue it in a new chat. "
                + "No topic lists recent threads.";
    }

    @Override
    public boolean threaded() {
        return false; // it picks its thread itself
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ((ObjectNode) s.get("properties")).putObject("topic").put("type", "string")
                .put("description", "Words of the topic, or a thread id like t3 (optional)");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        ThreadStore store = registry.threads(snapshot);
        List<ConversationThread> threads = store.recent();
        long now = registry.now();
        if (threads.isEmpty()) {
            out.line("No conversation threads yet for this workspace. They start with the first question.");
            return;
        }
        String topic = args.string("topic");
        if (topic == null) {
            out.line("Recent threads (newest first):");
            threads.stream().limit(MAX_LISTED).forEach(t -> out.line("  " + t.id + "  " + age(now - t.lastActive) + "  "
                    + t.calls + " question" + (t.calls == 1 ? "" : "s") + (t.branch.isEmpty() ? "" : "  [" + t.branch + "]")
                    + "  " + t.title));
            out.line("Recall one with topic: \"<id or words>\".");
            return;
        }
        ConversationThread t = store.byId(topic);
        if (t == null) {
            t = bestMatch(threads, LocateTool.queryTokens(topic));
        }
        if (t == null) {
            out.line("Not found: no thread about \"" + topic + "\". Recent: "
                    + String.join(", ", threads.stream().limit(MAX_LISTED).map(x -> x.id + " " + x.title).toList()));
            return;
        }
        Map<String, SourceFile> byKey = new HashMap<>();
        graph.files().forEach(f -> byKey.put(f.key(), f));
        out.line("Thread " + t.id + ": " + t.title + "  (" + t.calls + " questions, last " + age(now - t.lastActive)
                + (t.branch.isEmpty() ? "" : ", branch " + t.branch) + ")");
        out.line("Asked:");
        for (int i = 0; i < t.questions.size(); i++) {
            out.line("  " + (i + 1) + ". " + t.questions.get(i));
        }
        List<Map.Entry<String, Integer>> hits = new ArrayList<>(t.fileHits.entrySet());
        hits.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, String> keep = new HashMap<>();
        List<String> gone = new ArrayList<>();
        out.line("Central files (times cited):");
        for (Map.Entry<String, Integer> h : hits) {
            if (keep.size() >= MAX_FILES) {
                break;
            }
            SourceFile f = byKey.get(h.getKey());
            if (f == null) {
                gone.add(h.getKey().substring(h.getKey().indexOf(':') + 1));
                continue;
            }
            String changed = f.sha256().equals(t.files.get(f.key())) ? "" : "  (changed since)";
            out.line("  " + snapshot.workspace().display(f) + "  (" + h.getValue() + ")" + changed);
            keep.put(f.key(), f.sha256());
        }
        if (!gone.isEmpty()) {
            out.line("No longer in the workspace: " + String.join(", ", gone));
        }
        out.line("Continue it with thread: \"" + t.id + "\" (or just ask; related questions continue it).");

        // The recalling chat has only what this answer printed: forget the rest of "already given".
        t.lines.clear();
        t.nodes.clear();
        t.files.clear();
        t.files.putAll(keep);
        try {
            store.touch(t, now);
        } catch (IOException e) {
            throw new ToolException("Recalled, but could not save the thread: " + e.getMessage());
        }
    }

    static ConversationThread bestMatch(List<ConversationThread> threads, List<String> words) {
        Set<String> q = new LinkedHashSet<>(words);
        ConversationThread best = null;
        int bestShared = 0;
        for (ConversationThread t : threads) {
            Set<String> tw = new LinkedHashSet<>(t.words);
            if (t.title != null) {
                tw.addAll(LocateTool.queryTokens(t.title));
            }
            int shared = 0;
            for (String w : q) {
                if (tw.contains(w)) {
                    shared++;
                }
            }
            if (shared > bestShared) {
                bestShared = shared;
                best = t;
            }
        }
        return best;
    }

    static String age(long millis) {
        long min = millis / 60_000;
        if (min < 1) {
            return "just now";
        }
        if (min < 60) {
            return min + " min ago";
        }
        long h = min / 60;
        return h < 48 ? h + " h ago" : (h / 24) + " days ago";
    }
}
