package dev.agentideate.implement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/** Append-only {@code audit.jsonl}: every write agentideate makes to a project, and every undo. */
public final class AuditLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    public AuditLog(Path stateDir) {
        this.file = stateDir.resolve("audit.jsonl");
    }

    public void record(long now, String event, String plan, int version, String run, String via, List<String> files, String note)
            throws IOException {
        ObjectNode row = JSON.createObjectNode();
        row.put("ts", Instant.ofEpochMilli(now).toString());
        row.put("event", event);
        row.put("plan", plan);
        row.put("version", version);
        row.put("run", run);
        row.put("via", via);
        var arr = row.putArray("files");
        files.forEach(arr::add);
        if (note != null) {
            row.put("note", note);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Path file() {
        return file;
    }
}
