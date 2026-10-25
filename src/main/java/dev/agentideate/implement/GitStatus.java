package dev.agentideate.implement;

import dev.agentideate.exec.CommandRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which files git sees as changed in a root, as root-relative paths: used to tell whether anything
 * besides the plan's own files changed while it was applied. {@code null} when the root is not in
 * a git work tree or git cannot run, so the check is skipped rather than guessed.
 */
final class GitStatus {

    private GitStatus() {
    }

    static Set<String> changed(CommandRunner runner, Path root) {
        try {
            CommandRunner.Result inside = runner.run(List.of("git", "-C", root.toString(), "rev-parse", "--is-inside-work-tree"),
                    root, Duration.ofSeconds(20));
            if (!inside.ok() || !inside.output().trim().equals("true")) {
                return null;
            }
            CommandRunner.Result prefixR = runner.run(List.of("git", "-C", root.toString(), "rev-parse", "--show-prefix"),
                    root, Duration.ofSeconds(20));
            String prefix = prefixR.ok() ? prefixR.output().trim() : "";
            CommandRunner.Result status = runner.run(List.of("git", "-C", root.toString(), "status", "--porcelain",
                    "--untracked-files=all", "--", "."), root, Duration.ofSeconds(60));
            if (!status.ok()) {
                return null;
            }
            Set<String> out = new LinkedHashSet<>();
            for (String line : status.output().split("\n")) {
                if (line.length() < 4) {
                    continue;
                }
                String path = line.substring(3).trim();
                int arrow = path.indexOf(" -> ");
                if (arrow >= 0) {
                    path = path.substring(arrow + 4); // a rename: the new name
                }
                if (path.startsWith("\"") && path.endsWith("\"")) {
                    path = path.substring(1, path.length() - 1);
                }
                if (path.startsWith(prefix)) {
                    path = path.substring(prefix.length());
                }
                out.add(path);
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}
