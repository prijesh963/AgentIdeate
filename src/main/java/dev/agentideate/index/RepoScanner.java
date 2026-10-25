package dev.agentideate.index;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Walks one root and lists the files worth indexing, plus the build modules found on the way. */
final class RepoScanner {

    static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".gradle", ".idea", ".mvn", ".vscode", "build", "target", "out", "bin",
            "node_modules", "dist", ".agentideate");

    private static final Set<String> BUILD_FILES = Set.of("build.gradle", "build.gradle.kts", "pom.xml");

    /** Non-Java files that are listed (and later parsed) because they describe wiring or schema. */
    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            ".yml", ".yaml", ".properties", ".gradle", ".kts", ".sql", ".xml", ".avsc", ".proto");

    record ScannedFile(Path absolute, String relative, long size, long mtime) {
    }

    record ScanResult(List<ScannedFile> files, List<String> modules) {
    }

    static ScanResult scan(Path root) throws IOException {
        List<ScannedFile> files = new ArrayList<>();
        List<String> modules = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                for (String bf : BUILD_FILES) {
                    if (Files.isRegularFile(dir.resolve(bf))) {
                        modules.add(relative(root, dir));
                        break;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && wanted(file.getFileName().toString())) {
                    files.add(new ScannedFile(file, relative(root, file), attrs.size(),
                            attrs.lastModifiedTime().toMillis()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // unreadable entries are skipped, not fatal
            }
        });
        return new ScanResult(files, modules);
    }

    static boolean wanted(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return true;
        }
        for (String ext : CONFIG_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** The deepest module containing {@code relPath}, or {@code ""} for the root. */
    static String moduleOf(String relPath, List<String> modules) {
        String best = "";
        for (String m : modules) {
            if (!m.isEmpty() && (relPath.startsWith(m + "/")) && m.length() > best.length()) {
                best = m;
            }
        }
        return best;
    }

    private static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
