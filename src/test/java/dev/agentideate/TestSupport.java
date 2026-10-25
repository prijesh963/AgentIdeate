package dev.agentideate;

import dev.agentideate.tools.ToolRegistry;
import dev.agentideate.workspace.WorkspaceService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class TestSupport {

    private TestSupport() {
    }

    /** The sample workspace in {@code samples/shop}; read-only - copy it before changing anything. */
    public static Path shop() {
        String dir = System.getProperty("agentideate.samples", "samples");
        return Path.of(dir, "shop").toAbsolutePath();
    }

    /** The benchmark task file for the sample, {@code benchmark/shop.json}. */
    public static Path shopTasks() {
        return shop().getParent().getParent().resolve("benchmark/shop.json");
    }

    public static Path copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        return to;
    }

    public static ToolRegistry registry(Path home, Path... repos) {
        return ToolRegistry.standard(new WorkspaceService(new AppHome(home), List.of(repos), null));
    }
}
