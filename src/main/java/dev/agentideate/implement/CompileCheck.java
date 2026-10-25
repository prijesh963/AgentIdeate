package dev.agentideate.implement;

import dev.agentideate.exec.CommandRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

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
        boolean gradle = Files.exists(moduleDir.resolve("build.gradle")) || Files.exists(moduleDir.resolve("build.gradle.kts"));
        boolean maven = Files.exists(moduleDir.resolve("pom.xml"));
        if (gradle) {
            Path wrapper = findUp(root, moduleDir, WINDOWS ? "gradlew.bat" : "gradlew");
            List<String> cmd = new ArrayList<>();
            cmd.add(wrapper != null ? wrapper.toString() : WINDOWS ? "gradle.bat" : "gradle");
            cmd.addAll(List.of("-p", moduleDir.toString(), "compileJava", "compileTestJava", "--console=plain"));
            return cmd;
        }
        if (maven) {
            Path wrapper = findUp(root, moduleDir, WINDOWS ? "mvnw.cmd" : "mvnw");
            List<String> cmd = new ArrayList<>();
            cmd.add(wrapper != null ? wrapper.toString() : WINDOWS ? "mvn.cmd" : "mvn");
            cmd.addAll(List.of("-q", "-B", "-f", moduleDir.resolve("pom.xml").toString(), "test-compile"));
            return cmd;
        }
        return null;
    }

    /** The nearest {@code name} from the module up to the root (a multi-module build keeps its wrapper at the top). */
    private static Path findUp(Path root, Path from, String name) {
        for (Path d = from; d != null && d.startsWith(root); d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(name))) {
                return d.resolve(name);
            }
        }
        return null;
    }

    /** Whether build output holds compiler errors (as opposed to, say, a dependency that could not be downloaded). */
    static boolean hasCompileErrors(String output) {
        return output.lines().anyMatch(l -> l.matches(".*\\.java:\\d+: error: .*") || l.matches("^\\[ERROR] .*\\.java:\\[\\d+.*"));
    }
}
