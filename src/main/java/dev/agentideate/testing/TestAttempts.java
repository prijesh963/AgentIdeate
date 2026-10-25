package dev.agentideate.testing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Failed test runs per plan. After {@link #LIMIT} the agent is told to stop fixing and report, and
 * further runs are refused until the developer resets the count from the terminal
 * ({@code agentideate tests <plan> --reset}), so a fix loop cannot burn credits on its own.
 */
public final class TestAttempts {

    public static final int LIMIT = 3;
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public int failed;
    /** One line per run: when, which implementation run, and the result. */
    public List<String> history = new ArrayList<>();

    public static TestAttempts load(Path stateDir, String plan) throws IOException {
        Path f = file(stateDir, plan);
        return Files.isRegularFile(f) ? JSON.readValue(f.toFile(), TestAttempts.class) : new TestAttempts();
    }

    public void save(Path stateDir, String plan) throws IOException {
        Path f = file(stateDir, plan);
        Files.createDirectories(f.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), this);
    }

    /** The developer's reset: the count starts again; the history is kept. */
    public static TestAttempts reset(Path stateDir, String plan) throws IOException {
        TestAttempts a = load(stateDir, plan);
        a.failed = 0;
        a.history.add("reset by the developer");
        a.save(stateDir, plan);
        return a;
    }

    private static Path file(Path stateDir, String plan) {
        if (!plan.matches("p\\d+")) {
            throw new IllegalArgumentException("not a plan id: " + plan);
        }
        return stateDir.resolve("tests").resolve(plan + ".json");
    }
}
