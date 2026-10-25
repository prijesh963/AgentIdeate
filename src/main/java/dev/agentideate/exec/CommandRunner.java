package dev.agentideate.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Runs an external command. The real one only runs what {@link ProcessCommandRunner#allowed} permits. */
public interface CommandRunner {

    record Result(int exitCode, String output, boolean timedOut, long millis) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    /** @throws IOException when the command is not allowed or cannot be started */
    Result run(List<String> command, Path workDir, Duration timeout) throws IOException;
}
