package dev.agentideate.implement;

import dev.agentideate.exec.BuildTool;
import dev.agentideate.exec.CommandRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Compiles the modules a run changed with their own build: the Gradle wrapper (or {@code gradle})
 * running {@code compileJava compileTestJava} for the module, or the Maven wrapper (or {@code mvn})
 * running {@code test-compile}. A module with neither is skipped and says so.
 */
final class CompileCheck {

    record Outcome(String status, String command, String output, long millis) {
        /** {@code passed}, {@code failed}, {@code not-run} (no build tool or it could not start), {@code timeout}. */
        boolean failed() {
            return status.equals("failed");
        }
    }

    private CompileCheck() {
    }

    /** @param moduleDir the module's folder; {@code root} bounds the search for a wrapper */
    static Outcome run(CommandRunner runner, Path root, Path moduleDir, Duration timeout) {
        List<String> cmd = command(root, moduleDir);
        if (cmd == null) {
            return new Outcome("not-run", "", "no build.gradle or pom.xml for " + moduleDir, 0);
        }
        try {
            CommandRunner.Result r = runner.run(cmd, moduleDir, timeout);
            String status = r.timedOut() ? "timeout" : r.exitCode() == 0 ? "passed" : "failed";
            return new Outcome(status, String.join(" ", cmd), r.output(), r.millis());
        } catch (IOException e) {
            return new Outcome("not-run", String.join(" ", cmd), e.getMessage(), 0);
        }
    }

    static List<String> command(Path root, Path moduleDir) {
        BuildTool build = BuildTool.of(root, moduleDir);
        return build == null ? null : build.compileCommand();
    }

    /** Whether build output holds compiler errors (as opposed to, say, a dependency that could not be downloaded). */
    static boolean hasCompileErrors(String output) {
        return BuildTool.hasCompileErrors(output);
    }
}
