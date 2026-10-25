package dev.agentideate.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandRunnerTest {

    @Test
    void onlyReadOnlyGitAndCompileCommandsAreAllowed() {
        assertTrue(ProcessCommandRunner.allowed(List.of("git", "-C", "/r", "status", "--porcelain", "--untracked-files=all", "--", ".")));
        assertTrue(ProcessCommandRunner.allowed(List.of("git", "rev-parse", "--show-prefix")));
        assertTrue(ProcessCommandRunner.allowed(List.of("/r/gradlew", "-p", "/r/svc", "compileJava", "compileTestJava", "--console=plain")));
        assertTrue(ProcessCommandRunner.allowed(List.of("mvn", "-q", "-B", "-f", "/r/svc/pom.xml", "test-compile")));

        assertFalse(ProcessCommandRunner.allowed(List.of("git", "push")));
        assertFalse(ProcessCommandRunner.allowed(List.of("git", "status", "--porcelain", "-c", "core.pager=sh")));
        assertFalse(ProcessCommandRunner.allowed(List.of("git", "-C", "/r", "checkout", "--", ".")));
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "clean")));
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "-p", "/r")), "a build with no compile task");
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "compileJava", "--init-script", "evil.gradle")));
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "deploy")));
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "exec:exec")));
        assertFalse(ProcessCommandRunner.allowed(List.of("sh", "-c", "gradle compileJava")));
        assertFalse(ProcessCommandRunner.allowed(List.of("rm", "-rf", "/")));
        assertFalse(ProcessCommandRunner.allowed(List.of()));
    }

    @Test
    void testsRunOnlyFilteredByClassNames() {
        assertTrue(ProcessCommandRunner.allowed(List.of("gradlew", "-p", "/r/svc", "test", "--tests", "com.x.FooTest", "--tests",
                "com.x.Bar$InnerTest", "--console=plain")));
        assertTrue(ProcessCommandRunner.allowed(List.of("gradlew", "test", "--tests", "com.x.FooTest.method*")));
        assertTrue(ProcessCommandRunner.allowed(List.of("mvn", "-q", "-B", "-f", "/r/svc/pom.xml", "test", "-Dtest=com.x.FooTest,com.x.BarTest")));
        assertTrue(ProcessCommandRunner.allowed(List.of("mvn", "test", "-Dtest=FooTest#method")));

        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "test", "--tests", "x;rm -rf /")));
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "test", "--tests", "$(curl evil)")));
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "test", "--tests", "--init-script")), "a flag is not a filter");
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "test", "--tests")), "a filter flag without a filter");
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "--tests", "com.x.FooTest")), "a filter is not a task");
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "--tests", "com.x.FooTest", "test")), "--tests is Gradle's");
        assertFalse(ProcessCommandRunner.allowed(List.of("gradlew", "test", "-Dtest=com.x.FooTest")), "-Dtest is Maven's");
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "test", "-Dtest=")));
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "test", "-Dtest=a;b")));
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "test", "-Dtest=Foo,../x")));
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "test", "-Dmaven.test.failure.ignore=true")));
        assertFalse(ProcessCommandRunner.allowed(List.of("mvn", "test", "-DargLine=-javaagent:evil.jar")));
    }

    @Test
    void aDisallowedCommandIsNeverStarted(@TempDir Path tmp) {
        IOException e = assertThrows(IOException.class,
                () -> new ProcessCommandRunner().run(List.of("rm", "-rf", tmp.toString()), tmp, Duration.ofSeconds(5)));
        assertTrue(e.getMessage().startsWith("Not an allowed command"));
        assertTrue(Files.exists(tmp));
    }

    @Test
    void gitStatusRunsForReal(@TempDir Path tmp) throws Exception {
        Process init = new ProcessBuilder("git", "init", "-q").directory(tmp.toFile()).start();
        assertEquals(0, init.waitFor());
        Files.writeString(tmp.resolve("a.txt"), "x");
        CommandRunner.Result r = new ProcessCommandRunner().run(
                List.of("git", "-C", tmp.toString(), "status", "--porcelain", "--untracked-files=all", "--", "."), tmp, Duration.ofSeconds(30));
        assertTrue(r.ok(), r.output());
        assertEquals("?? a.txt", r.output().trim());
    }
}
