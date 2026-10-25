package dev.agentideate.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Plans of one workspace, one JSON file each under {@code plans/} in its state directory. */
public final class PlanStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path dir;

    public PlanStore(Path stateDir) {
        this.dir = stateDir.resolve("plans");
    }

    public synchronized List<Plan> all() throws IOException {
        List<Plan> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().matches("p\\d+\\.json")).toList()) {
                try {
                    out.add(JSON.readValue(p.toFile(), Plan.class));
                } catch (IOException e) {
                    // an unreadable plan file is skipped, not fatal
                }
            }
        }
        out.sort(Comparator.comparingLong((Plan p) -> p.updated).reversed());
        return out;
    }

    public synchronized Plan get(String id) throws IOException {
        if (id == null || !id.matches("p\\d+")) {
            return null;
        }
        Path p = dir.resolve(id + ".json");
        return Files.isRegularFile(p) ? JSON.readValue(p.toFile(), Plan.class) : null;
    }

    public synchronized String nextId() throws IOException {
        int max = 0;
        for (Plan p : all()) {
            max = Math.max(max, Integer.parseInt(p.id.substring(1)));
        }
        return "p" + (max + 1);
    }

    public synchronized void save(Plan plan) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(plan.id + ".json.tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), plan);
        Files.move(tmp, dir.resolve(plan.id + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
