package dev.agentideate.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The only way agentideate runs a process. No shell is involved, and a command must match the
 * allowlist exactly: read-only {@code git status}/{@code git rev-parse}, Gradle compile tasks,
 * Maven compile goals. Output is capped, and a command that runs past its timeout is killed.
 */
public final class ProcessCommandRunner implements CommandRunner {

    static final int MAX_OUTPUT = 2 * 1024 * 1024;

    private static final Set<String> GIT_SUBCOMMANDS = Set.of("status", "rev-parse");
    private static final Set<String> GIT_FLAGS = Set.of("--porcelain", "--porcelain=v1", "--untracked-files=all",
            "--show-prefix", "--show-toplevel", "--is-inside-work-tree", "--");
    private static final Set<String> GRADLE_ARGS = Set.of("compileJava", "compileTestJava", "--console=plain", "-q", "--offline");
    private static final Set<String> MAVEN_ARGS = Set.of("compile", "test-compile", "-q", "-B", "-o");

    @Override
    public Result run(List<String> command, Path workDir, Duration timeout) throws IOException {
        if (!allowed(command)) {
            throw new IOException("Not an allowed command: " + String.join(" ", command));
        }
        long start = System.nanoTime();
        Process p = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(p.getInputStream(), buf), "agentideate-process-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            reader.join(5_000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.get(0));
        }
        String out;
        synchronized (buf) {
            out = buf.toString(StandardCharsets.UTF_8);
        }
        return new Result(finished ? p.exitValue() : -1, out, !finished, (System.nanoTime() - start) / 1_000_000);
    }

    private static void drain(InputStream in, ByteArrayOutputStream buf) {
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                synchronized (buf) {
                    if (buf.size() < MAX_OUTPUT) {
                        buf.write(chunk, 0, Math.min(n, MAX_OUTPUT - buf.size()));
                    }
                }
            }
        } catch (IOException ignored) {
            // the process ended or was killed
        }
    }

    /**
     * Whether a command may run: {@code git [-C dir] status|rev-parse} with read-only flags (and
     * paths after {@code --}); a Gradle wrapper or {@code gradle} with {@code -p dir} and compile
     * tasks; a Maven wrapper or {@code mvn} with {@code -f pom} and compile goals. Nothing else.
     */
    public static boolean allowed(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String exe = Path.of(command.get(0)).getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> args = command.subList(1, command.size());
        return switch (exe) {
            case "git", "git.exe" -> gitAllowed(args);
            case "gradlew", "gradlew.bat", "gradle", "gradle.bat" -> withDirFlag(args, "-p", GRADLE_ARGS);
            case "mvnw", "mvnw.cmd", "mvn", "mvn.cmd" -> withDirFlag(args, "-f", MAVEN_ARGS);
            default -> false;
        };
    }

    private static boolean gitAllowed(List<String> args) {
        int i = 0;
        if (args.size() >= 2 && args.get(0).equals("-C")) {
            i = 2;
        }
        if (i >= args.size() || !GIT_SUBCOMMANDS.contains(args.get(i))) {
            return false;
        }
        boolean afterDashDash = false;
        for (String a : args.subList(i + 1, args.size())) {
            if (afterDashDash) {
                continue; // pathspecs
            }
            if (a.equals("--")) {
                afterDashDash = true;
            } else if (!GIT_FLAGS.contains(a)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withDirFlag(List<String> args, String dirFlag, Set<String> allowedArgs) {
        boolean anyTask = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals(dirFlag) && i + 1 < args.size()) {
                i++;
            } else if (allowedArgs.contains(a)) {
                anyTask |= !a.startsWith("-");
            } else {
                return false;
            }
        }
        return anyTask;
    }
}
