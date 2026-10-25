package dev.agentideate.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolTest {

    @Test
    void gradleAndMavenCommandsAreTheOnesTheAllowlistPermits(@TempDir Path root) throws Exception {
        Path gradleModule = Files.createDirectories(root.resolve("a"));
        Files.writeString(gradleModule.resolve("build.gradle.kts"), "");
        Files.writeString(root.resolve("gradlew"), "");
        Path mavenModule = Files.createDirectories(root.resolve("b"));
        Files.writeString(mavenModule.resolve("pom.xml"), "<project/>");

        BuildTool gradle = BuildTool.of(root, gradleModule);
        assertTrue(gradle.gradle());
        assertTrue(gradle.executable().endsWith("gradlew"), "the wrapper at the top of a multi-module build");
        List<String> test = gradle.testCommand(List.of("com.x.FooTest", "com.x.BarTest"));
        assertEquals(List.of(gradle.executable(), "-p", gradleModule.toString(), "test", "--tests", "com.x.FooTest",
                "--tests", "com.x.BarTest", "--console=plain"), test);
        assertTrue(ProcessCommandRunner.allowed(test));
        assertTrue(ProcessCommandRunner.allowed(gradle.compileCommand()));
        assertEquals(List.of(gradleModule.resolve("build/test-results")), gradle.reportDirs());

        BuildTool maven = BuildTool.of(root, mavenModule);
        List<String> mvnTest = maven.testCommand(List.of("com.x.FooTest", "com.x.BarTest"));
        assertEquals(List.of(maven.executable(), "-q", "-B", "-f", mavenModule.resolve("pom.xml").toString(), "test",
                "-Dtest=com.x.FooTest,com.x.BarTest"), mvnTest);
        assertTrue(ProcessCommandRunner.allowed(mvnTest));
        assertTrue(ProcessCommandRunner.allowed(maven.compileCommand()));

        assertNull(BuildTool.of(root, Files.createDirectories(root.resolve("c"))));
        assertEquals(gradleModule, BuildTool.moduleOf(root, gradleModule.resolve("src/main/java/X.java")));
    }
}
