package dev.agentideate.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Summarizes {@code ledger.jsonl}: how often each tool was used, what its answers cost, what the
 * files they cite would have cost to read, and what threads saved by not repeating themselves.
 * The evidence for "how much did this save", computed from what actually happened.
 */
public final class LedgerReport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Totals {
        int calls;
        long returned;
        long referenced;
        long omitted;
        int continued;
    }

    private LedgerReport() {
    }

    /**
     * @param stateDirs workspace state directories whose ledgers to read
     * @param sinceMs   only rows at or after this time
     */
    public static String render(List<Path> stateDirs, long sinceMs, String scope) throws IOException {
        Map<String, Totals> byTool = new TreeMap<>();
        Totals all = new Totals();
        Set<String> threads = new HashSet<>();
        int unreadable = 0;
        for (Path dir : stateDirs) {
            Path ledger = dir.resolve("ledger.jsonl");
            if (!Files.isRegularFile(ledger)) {
                continue;
            }
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row;
                try {
                    row = JSON.readTree(line);
                    if (Instant.parse(row.path("ts").asText()).toEpochMilli() < sinceMs) {
                        continue;
                    }
                } catch (IOException | RuntimeException e) {
                    unreadable++;
                    continue;
                }
                Totals t = byTool.computeIfAbsent(row.path("tool").asText("?"), k -> new Totals());
                for (Totals x : List.of(t, all)) {
                    x.calls++;
                    x.returned += row.path("returnedBytes").asLong();
                    x.referenced += row.path("referencedBytes").asLong();
                    x.omitted += row.path("omittedBytes").asLong();
                    x.continued += row.path("continued").asBoolean() ? 1 : 0;
                }
                if (row.hasNonNull("thread")) {
                    threads.add(dir + "#" + row.get("thread").asText());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (all.calls == 0) {
            sb.append("No agentideate calls recorded for ").append(scope).append(".\n");
            return sb.toString();
        }
        sb.append("agentideate usage for ").append(scope).append(": ").append(all.calls).append(" calls\n\n");
        sb.append(String.format(" %-16s %6s %10s %12s %14s%n", "tool", "calls", "answers", "cited files", "not repeated"));
        byTool.forEach((tool, t) -> sb.append(String.format(" %-16s %6d %10s %12s %14s%n", tool, t.calls,
                tokens(t.returned), tokens(t.referenced), t.omitted == 0 ? "-" : tokens(t.omitted))));
        long avoided = Math.max(0, all.referenced - all.returned) + all.omitted;
        sb.append('\n');
        sb.append("Answers: ").append(tokens(all.returned)).append(" tokens. Files they cite: ").append(tokens(all.referenced))
                .append(" tokens. Not repeated in threads: ").append(tokens(all.omitted)).append(" tokens.\n");
        sb.append("Estimated reading avoided: ~").append(tokens(avoided)).append(" tokens");
        if (all.referenced > 0) {
            sb.append(" (").append(Math.round(100.0 * avoided / (all.referenced + all.omitted))).append("% of what the answers stood in for)");
        }
        sb.append(".\n");
        if (!threads.isEmpty()) {
            sb.append("Threads: ").append(threads.size()).append(" topics, ").append(all.continued).append(" follow-up questions continued one.\n");
        }
        if (unreadable > 0) {
            sb.append(unreadable).append(" unreadable ledger lines skipped.\n");
        }
        sb.append("\"Cited files\" is what opening every file an answer cites would cost; an agent might have read fewer\n")
                .append("or more. Tokens are estimated at 4 characters. Per-request tool definitions are not included.\n");
        return sb.toString();
    }

    /** Every workspace state directory under the agentideate home. */
    public static List<Path> allWorkspaces(Path home) throws IOException {
        Path ws = home.resolve("workspaces");
        if (!Files.isDirectory(ws)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(ws)) {
            return new ArrayList<>(s.filter(Files::isDirectory).sorted().toList());
        }
    }

    static String tokens(long chars) {
        long t = Math.max(0, chars / 4);
        return t >= 1000 ? String.format("%.1fk", t / 1000.0) : Long.toString(t);
    }
}
