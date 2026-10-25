package dev.agentideate.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One line of questioning about the workspace, remembered across tool calls: what was asked, and
 * what was already given (files, answer lines, flow nodes), each tied to the file version it came
 * from so a later edit makes it fresh again.
 *
 * <p>A plain mutable bean: it is persisted as JSON in the workspace's state directory.
 */
public final class ConversationThread {

    public String id;
    public String title;
    public long created;
    public long lastActive;
    public String branch = "";
    public int calls;
    public long omittedChars;

    /** The questions in order, as {@code tool argument}. */
    public List<String> questions = new ArrayList<>();

    /** Words of every question, for matching a later question or a recall topic. */
    public Set<String> words = new LinkedHashSet<>();

    /** Files cited so far: file key to the content hash it had when cited. */
    public Map<String, String> files = new LinkedHashMap<>();

    /** How often each file was cited, so a recall can lead with the central ones. */
    public Map<String, Integer> fileHits = new LinkedHashMap<>();

    /** Answer lines already given (with full paths), each with the files it depends on. */
    public Map<String, List<String>> lines = new LinkedHashMap<>();

    /**
     * Flow nodes already expanded, each with every file of the call tree it expanded, so a change
     * anywhere below a node makes the node fresh again.
     */
    public Map<String, List<String>> nodes = new LinkedHashMap<>();

    public ConversationThread() {
    }

    ConversationThread(String id, long now, String branch) {
        this.id = id;
        this.created = now;
        this.lastActive = now;
        this.branch = branch == null ? "" : branch;
    }

    /** Forgets everything given from files whose content changed (or vanished) since it was given. */
    public int invalidate(Map<String, String> currentHashes) {
        Set<String> stale = new LinkedHashSet<>();
        files.forEach((key, hash) -> {
            String now = currentHashes.get(key);
            if (now == null || !now.equals(hash)) {
                stale.add(key);
            }
        });
        if (stale.isEmpty()) {
            return 0;
        }
        stale.forEach(files::remove);
        lines.values().removeIf(deps -> deps.stream().anyMatch(stale::contains));
        nodes.values().removeIf(deps -> deps.stream().anyMatch(stale::contains));
        return stale.size();
    }
}
