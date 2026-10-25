package dev.agentideate.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A module's own build: Gradle (its wrapper, or {@code gradle}) when it has a {@code build.gradle(.kts)},
 * Maven (its wrapper, or {@code mvn}) when it has a {@code pom.xml}. Builds the commands the compile
 * check and the test run use, which are exactly what {@link ProcessCommandRunner#allowed} permits.
 */
public record BuildTool(boolean gradle, String executable, Path moduleDir) {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** The module's build, or {@code null} when it has neither build file. {@code root} bounds the search for a wrapper. */
    public static BuildTool of(Path root, Path moduleDir) {
        if (Files.exists(moduleDir.resolve("build.gradle")) || Files.exists(moduleDir.resolve("build.gradle.kts"))) {
            Path wrapper = findUp(root, moduleDir, WINDOWS ? "gradlew.bat" : "gradlew");
            return new BuildTool(true, wrapper != null ? wrapper.toString() : WINDOWS ? "gradle.bat" : "gradle", moduleDir);
        }
        if (Files.exists(moduleDir.resolve("pom.xml"))) {
            Path wrapper = findUp(root, moduleDir, WINDOWS ? "mvnw.cmd" : "mvnw");
            return new BuildTool(false, wrapper != null ? wrapper.toString() : WINDOWS ? "mvn.cmd" : "mvn", moduleDir);
        }
        return null;
    }

    /** The nearest folder holding a build file, from {@code file}'s folder up to {@code root}. */
    public static Path moduleOf(Path root, Path file) {
        for (Path d = file.getParent(); d != null && d.startsWith(root); d = d.getParent()) {
            if (Files.exists(d.resolve("build.gradle")) || Files.exists(d.resolve("build.gradle.kts")) || Files.exists(d.resolve("pom.xml"))) {
                return d;
            }
        }
        return null;
    }

    public List<String> compileCommand() {
        List<String> cmd = new ArrayList<>(List.of(executable));
        if (gradle) {
            cmd.addAll(List.of("-p", moduleDir.toString(), "compileJava", "compileTestJava", "--console=plain"));
        } else {
            cmd.addAll(List.of("-q", "-B", "-f", moduleDir.resolve("pom.xml").toString(), "test-compile"));
        }
        return cmd;
    }

    /** Runs only {@code testClasses} (fully qualified names). */
    public List<String> testCommand(List<String> testClasses) {
        List<String> cmd = new ArrayList<>(List.of(executable));
        if (gradle) {
            cmd.addAll(List.of("-p", moduleDir.toString(), "test"));
            for (String t : testClasses) {
                cmd.add("--tests");
                cmd.add(t);
            }
            cmd.add("--console=plain");
        } else {
            cmd.addAll(List.of("-q", "-B", "-f", moduleDir.resolve("pom.xml").toString(), "test", "-Dtest=" + String.join(",", testClasses)));
        }
        return cmd;
    }

    /** Where this build leaves JUnit XML reports. */
    public List<Path> reportDirs() {
        return gradle ? List.of(moduleDir.resolve("build/test-results"))
                : List.of(moduleDir.resolve("target/surefire-reports"), moduleDir.resolve("target/failsafe-reports"));
    }

    /** Whether build output holds compiler errors (as opposed to, say, a dependency that could not be downloaded). */
    public static boolean hasCompileErrors(String output) {
        return output.lines().anyMatch(l -> l.matches(".*\\.java:\\d+: error: .*") || l.matches("^\\[ERROR] .*\\.java:\\[\\d+.*"));
    }

    private static Path findUp(Path root, Path from, String name) {
        for (Path d = from; d != null && d.startsWith(root); d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(name))) {
                return d.resolve(name);
            }
        }
        return null;
    }
}
