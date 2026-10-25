package dev.agentideate.workspace;

import dev.agentideate.index.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of repository roots being worked on: one repo, or several microservice repos at once.
 * Roots are normalized and sorted, so the same set always maps to the same id and cache.
 */
public record Workspace(List<Root> roots) {

    public record Root(String name, Path path) {
    }

    public static Workspace of(List<Path> paths) {
        Set<Path> normalized = new LinkedHashSet<>();
        for (Path p : paths) {
            Path abs = p.toAbsolutePath().normalize();
            try {
                abs = abs.toRealPath();
            } catch (IOException ignored) {
                // keep the normalized path; a missing root is reported by the indexer
            }
            normalized.add(abs);
        }
        List<Path> sorted = new ArrayList<>(normalized);
        sorted.sort(null);
        List<Root> roots = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Path p : sorted) {
            String base = p.getFileName() == null ? "root" : p.getFileName().toString();
            String name = base;
            for (int i = 2; !used.add(name); i++) {
                name = base + "-" + i;
            }
            roots.add(new Root(name, p));
        }
        return new Workspace(List.copyOf(roots));
    }

    public boolean multiRoot() {
        return roots.size() > 1;
    }

    public Root root(String name) {
        for (Root r : roots) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    public String id() {
        StringBuilder joined = new StringBuilder();
        roots.forEach(r -> joined.append(r.path()).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            String first = roots.isEmpty() ? "empty" : roots.get(0).name().replaceAll("[^A-Za-z0-9._-]", "_");
            return first + "-" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How a file is shown in answers: root-relative, prefixed by its root only when there are several. */
    public String display(SourceFile f) {
        return multiRoot() ? f.root() + "/" + f.path() : f.path();
    }

    public Path absolute(SourceFile f) {
        Root r = root(f.root());
        return r == null ? Path.of(f.path()) : r.path().resolve(f.path());
    }

    /**
     * The checked-out branch of each root ({@code name=branch}, comma-joined), read from
     * {@code .git/HEAD}; a detached head is its commit prefix, a non-git root contributes nothing.
     */
    public String branch() {
        List<String> parts = new ArrayList<>();
        for (Root r : roots) {
            String b = gitBranch(r.path());
            if (b != null) {
                parts.add(multiRoot() ? r.name() + "=" + b : b);
            }
        }
        return String.join(",", parts);
    }

    static String gitBranch(Path root) {
        try {
            Path git = root.resolve(".git");
            if (Files.isRegularFile(git)) { // a worktree: ".git" is a file pointing at the real git dir
                String pointer = Files.readString(git).trim();
                if (!pointer.startsWith("gitdir:")) {
                    return null;
                }
                git = root.resolve(pointer.substring("gitdir:".length()).trim());
            }
            Path head = git.resolve("HEAD");
            if (!Files.isRegularFile(head)) {
                return null;
            }
            String h = Files.readString(head).trim();
            if (h.startsWith("ref:")) {
                String ref = h.substring(4).trim();
                return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
            }
            return h.length() >= 12 ? h.substring(0, 12) : h;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public boolean allRootsExist() {
        return roots.stream().allMatch(r -> Files.isDirectory(r.path()));
    }
}
