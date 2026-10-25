package dev.agentideate.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadStoreTest {

    @TempDir
    Path state;

    private static final long T0 = 1_000_000_000L;

    private ConversationThread started(ThreadStore store, Set<String> files, Set<String> words) throws Exception {
        ThreadStore.Choice c = store.choose(null, files, words, "main", T0);
        Map<String, String> hashes = new java.util.LinkedHashMap<>();
        files.forEach(f -> hashes.put(f, "h-" + f));
        store.record(c.thread(), "q1", words, hashes, Map.of("line one", List.copyOf(files)), Map.of(), 0, T0);
        return c.thread();
    }

    @Test
    void aRelatedQuestionContinuesAndAnUnrelatedOneStartsFresh() throws Exception {
        ThreadStore store = new ThreadStore(state);
        ConversationThread t1 = started(store, Set.of("r:A.java", "r:B.java", "r:C.java"), Set.of("order", "cancel"));
        ThreadStore.Choice related = store.choose(null, Set.of("r:B.java", "r:D.java"), Set.of("refund"), "main", T0 + 1000);
        assertTrue(related.continued());
        assertSame(t1, related.thread());
        ThreadStore.Choice unrelated = store.choose(null, Set.of("r:X.java"), Set.of("stock"), "main", T0 + 1000);
        assertFalse(unrelated.continued());
        assertEquals("t2", unrelated.thread().id);
    }

    @Test
    void wordsAloneCanRelateAQuestionThatCitesNoFiles() throws Exception {
        ThreadStore store = new ThreadStore(state);
        started(store, Set.of("r:A.java"), Set.of("invoice", "approval"));
        assertTrue(store.choose(null, Set.of(), Set.of("invoice", "approval", "status"), "main", T0 + 1).continued());
    }

    @Test
    void explicitNewAndExplicitIdWin() throws Exception {
        ThreadStore store = new ThreadStore(state);
        ConversationThread t1 = started(store, Set.of("r:A.java"), Set.of("order"));
        assertFalse(store.choose("new", Set.of("r:A.java"), Set.of("order"), "main", T0).continued());
        ThreadStore.Choice byId = store.choose("t1", Set.of("r:Z.java"), Set.of("zzz"), "main", T0);
        assertTrue(byId.continued());
        assertSame(t1, byId.thread());
        ThreadStore.Choice unknown = store.choose("t42", Set.of(), Set.of(), "main", T0);
        assertFalse(unknown.continued());
        assertTrue(unknown.reason().contains("no thread 't42'"));
    }

    @Test
    void anIdleThreadOrAnotherBranchIsNotContinued() throws Exception {
        ThreadStore store = new ThreadStore(state);
        started(store, Set.of("r:A.java"), Set.of("order"));
        assertFalse(store.choose(null, Set.of("r:A.java"), Set.of("order"), "main",
                T0 + ThreadStore.CONTINUE_WINDOW_MS + 1).continued());
        assertFalse(store.choose(null, Set.of("r:A.java"), Set.of("order"), "feature-x", T0 + 1).continued());
    }

    @Test
    void threadsSurviveARestart() throws Exception {
        started(new ThreadStore(state), Set.of("r:A.java"), Set.of("order"));
        ThreadStore reloaded = new ThreadStore(state);
        ConversationThread t = reloaded.byId("t1");
        assertEquals("q1", t.title);
        assertEquals(Map.of("r:A.java", "h-r:A.java"), t.files);
        assertTrue(t.lines.containsKey("line one"));
    }

    @Test
    void aChangedFileInvalidatesWhatWasGivenFromIt() {
        ConversationThread t = new ConversationThread("t1", T0, "main");
        t.files.put("r:A.java", "a1");
        t.files.put("r:B.java", "b1");
        t.lines.put("from A", List.of("r:A.java"));
        t.lines.put("from B", List.of("r:B.java"));
        t.lines.put("from both", List.of("r:A.java", "r:B.java"));
        t.nodes.put("A#m", List.of("r:A.java"));
        t.nodes.put("B#m", List.of("r:B.java"));
        t.nodes.put("Top#m", List.of("r:B.java", "r:A.java"));
        assertEquals(1, t.invalidate(Map.of("r:A.java", "a2", "r:B.java", "b1")));
        assertEquals(Set.of("r:B.java"), t.files.keySet());
        assertEquals(Set.of("from B"), t.lines.keySet());
        assertEquals(Set.of("B#m"), t.nodes.keySet());
        assertEquals(1, t.invalidate(Map.of()), "a deleted file is stale too");
    }

    @Test
    void onlyTheMostRecentThreadsAreKept() throws Exception {
        ThreadStore store = new ThreadStore(state);
        for (int i = 0; i < ThreadStore.MAX_THREADS + 5; i++) {
            ThreadStore.Choice c = store.choose("new", Set.of(), Set.of(), "main", T0 + i);
            store.record(c.thread(), "q" + i, Set.of(), Map.of(), Map.of(), Map.of(), 0, T0 + i);
        }
        List<ConversationThread> kept = new ThreadStore(state).recent();
        assertEquals(ThreadStore.MAX_THREADS, kept.size());
        assertEquals("q" + (ThreadStore.MAX_THREADS + 4), kept.get(0).title);
        assertNotEquals("q0", kept.get(kept.size() - 1).title);
    }

    @Test
    void overlapIsSharedOverTheSmallerSet() {
        assertEquals(1.0, ThreadStore.overlap(Set.of("a"), Set.of("a", "b", "c")));
        assertEquals(0.5, ThreadStore.overlap(Set.of("a", "x"), Set.of("a", "b", "c")));
        assertEquals(0.0, ThreadStore.overlap(Set.of(), Set.of("a")));
    }
}
