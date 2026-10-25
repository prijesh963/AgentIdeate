package dev.agentideate.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Append-only record of every tool call, one JSON object per line in {@code ledger.jsonl}: the raw
 * material for showing what the index saved. Never fails a tool call: a ledger that cannot be
 * written is skipped.
 */
public final class Ledger {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Ledger() {
    }

    public static void record(Path stateDir, String tool, long millis, int returnedBytes,
                              int referencedFiles, long referencedBytes) {
        try {
            Files.createDirectories(stateDir);
            ObjectNode row = JSON.createObjectNode()
                    .put("ts", Instant.now().toString())
                    .put("tool", tool)
                    .put("ms", millis)
                    .put("returnedBytes", returnedBytes)
                    .put("referencedFiles", referencedFiles)
                    .put("referencedBytes", referencedBytes);
            Files.writeString(stateDir.resolve("ledger.jsonl"), JSON.writeValueAsString(row) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the ledger is evidence, not a dependency
        }
    }
}
