package dev.agentideate.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The conversation threads of one workspace, in {@code threads.json}. Decides, without a model,
 * whether a question continues a recent thread or starts a new one:
 *
 * <ul>
 *   <li>{@code thread: "new"} always starts one; a thread id continues that thread;</li>
 *   <li>otherwise the question's cited files and words are compared with each recent thread on the
 *       same branch: overlap of files (shared files over the smaller set) plus half the overlap of
 *       words. The best thread at or above {@link #THRESHOLD} continues; else a new thread starts.</li>
 * </ul>
 */
public final class ThreadStore {

    /** A thread idle longer than this is not continued automatically (it can still be recalled). */
    public static final long CONTINUE_WINDOW_MS = 6L * 60 * 60 * 1000;
    static final double THRESHOLD = 0.3;
    static final int MAX_THREADS = 30;
    static final int MAX_LINES = 3000;
    static final int MAX_QUESTIONS = 50;

    public record Choice(ConversationThread thread, boolean continued, String reason) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path file;
    private final List<ConversationThread> threads = new ArrayList<>();

    public ThreadStore(Path stateDir) {
        this.file = stateDir.resolve("threads.json");
        if (Files.isRegularFile(file)) {
            try {
                threads.addAll(JSON.readValue(file.toFile(), new TypeReference<List<ConversationThread>>() { }));
            } catch (IOException e) {
                // unreadable memory is dropped; questions simply start fresh threads
            }
        }
    }

    public synchronized List<ConversationThread> recent() {
        List<ConversationThread> out = new ArrayList<>(threads);
        out.sort(Comparator.comparingLong((ConversationThread t) -> t.lastActive).reversed());
        return out;
    }

    public synchronized ConversationThread byId(String id) {
        for (ConversationThread t : threads) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    public synchronized Choice choose(String explicit, Set<String> fileKeys, Set<String> words, String branch, long now) {
        if (explicit != null && explicit.equalsIgnoreCase("new")) {
            return new Choice(newThread(now, branch), false, "new thread, as asked");
        }
        if (explicit != null) {
            ConversationThread t = byId(explicit);
            if (t != null) {
                return new Choice(t, true, "continued, as asked");
            }
            return new Choice(newThread(now, branch), false, "new thread: no thread '" + explicit + "'");
        }
        ConversationThread best = null;
        double bestScore = 0;
        for (ConversationThread t : recent()) {
            if (now - t.lastActive > CONTINUE_WINDOW_MS || !t.branch.equals(branch == null ? "" : branch)) {
                continue;
            }
            double s = score(t, fileKeys, words);
            if (s > bestScore) {
                bestScore = s;
                best = t;
            }
        }
        if (best != null && bestScore >= THRESHOLD) {
            return new Choice(best, true, "continued");
        }
        return new Choice(newThread(now, branch), false, "new topic");
    }

    static double score(ConversationThread t, Set<String> fileKeys, Set<String> words) {
        double w = overlap(words, t.words);
        if (fileKeys.isEmpty() || t.files.isEmpty()) {
            return w;
        }
        return overlap(fileKeys, t.files.keySet()) + 0.5 * w;
    }

    /** Shared elements over the size of the smaller set: 1.0 when one set contains the other. */
    static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        int shared = 0;
        for (String x : small) {
            if (large.contains(x)) {
                shared++;
            }
        }
        return (double) shared / small.size();
    }

    private ConversationThread newThread(long now, String branch) {
        Set<String> ids = new HashSet<>();
        threads.forEach(t -> ids.add(t.id));
        int n = 1;
        while (ids.contains("t" + n)) {
            n++;
        }
        return new ConversationThread("t" + n, now, branch);
    }

    /** Records one answered question in its thread and saves. */
    public synchronized void record(ConversationThread t, String question, Set<String> words, Map<String, String> fileHashes,
                                    Map<String, List<String>> newLines, Map<String, List<String>> newNodes, long omittedChars,
                                    long now) throws IOException {
        if (!threads.contains(t)) {
            threads.add(t);
        }
        if (t.title == null) {
            t.title = question;
        }
        if (t.questions.size() < MAX_QUESTIONS) {
            t.questions.add(question);
        }
        t.words.addAll(words);
        t.files.putAll(fileHashes);
        fileHashes.keySet().forEach(k -> t.fileHits.merge(k, 1, Integer::sum));
        for (Map.Entry<String, List<String>> e : newLines.entrySet()) {
            if (t.lines.size() >= MAX_LINES) {
                break;
            }
            t.lines.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, List<String>> e : newNodes.entrySet()) {
            if (t.nodes.size() >= MAX_LINES) {
                break;
            }
            t.nodes.put(e.getKey(), e.getValue());
        }
        t.calls++;
        t.omittedChars += omittedChars;
        t.lastActive = now;
        save();
    }

    /** Marks a thread as the active one (after a recall), without recording a question. */
    public synchronized void touch(ConversationThread t, long now) throws IOException {
        t.lastActive = now;
        save();
    }

    private void save() throws IOException {
        List<ConversationThread> keep = recent();
        if (keep.size() > MAX_THREADS) {
            threads.retainAll(keep.subList(0, MAX_THREADS));
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("threads.json.tmp");
        JSON.writeValue(tmp.toFile(), threads);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
