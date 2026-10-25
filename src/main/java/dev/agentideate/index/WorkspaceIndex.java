package dev.agentideate.index;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentideate.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The index of one workspace, kept in memory and mirrored to {@code index.json} in the workspace's
 * state directory. {@link #refresh()} is cheap when nothing changed: it compares size and mtime,
 * re-hashes only files that look different, and re-parses only files whose hash actually changed.
 */
public final class WorkspaceIndex {

    /** Bumped whenever the shape of {@link SourceFile} changes, so an old cache is rebuilt, not misread. */
    static final int CACHE_VERSION = 2;

    /** Files above this size are listed but not parsed: generated sources, mostly. */
    static final long MAX_PARSE_BYTES = 2 * 1024 * 1024;

    public record Stats(int files, int javaFiles, int reparsed, int removed, int parseErrors, long millis,
                        boolean changed) {
    }

    record CacheFile(int version, List<SourceFile> files, Map<String, List<String>> modules) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Workspace workspace;
    private final Path cacheFile;
    private final JavaFileParser parser = new JavaFileParser();
    private Map<String, SourceFile> files = new LinkedHashMap<>();
    private Map<String, List<String>> modules = new LinkedHashMap<>();
    private Stats lastStats;

    public WorkspaceIndex(Workspace workspace, Path stateDir) {
        this.workspace = workspace;
        this.cacheFile = stateDir.resolve("index.json");
        loadCache();
    }

    public Workspace workspace() {
        return workspace;
    }

    public Collection<SourceFile> files() {
        return files.values();
    }

    /** Build modules per root name, as root-relative directories ({@code ""} is the root itself). */
    public Map<String, List<String>> modules() {
        return modules;
    }

    public Stats lastStats() {
        return lastStats;
    }

    public synchronized Stats refresh() throws IOException {
        long start = System.nanoTime();
        Map<String, SourceFile> next = new LinkedHashMap<>();
        Map<String, List<String>> nextModules = new LinkedHashMap<>();
        int reparsed = 0;
        for (Workspace.Root root : workspace.roots()) {
            if (!Files.isDirectory(root.path())) {
                throw new IOException("Repository folder not found: " + root.path());
            }
            RepoScanner.ScanResult scan = RepoScanner.scan(root.path());
            nextModules.put(root.name(), List.copyOf(scan.modules()));
            for (RepoScanner.ScannedFile sf : scan.files()) {
                String key = root.name() + ":" + sf.relative();
                String module = RepoScanner.moduleOf(sf.relative(), scan.modules());
                SourceFile cached = files.get(key);
                if (cached != null && cached.size() == sf.size() && cached.mtime() == sf.mtime()
                        && cached.module().equals(module)) {
                    next.put(key, cached);
                    continue;
                }
                byte[] bytes = Files.readAllBytes(sf.absolute());
                String hash = sha256(bytes);
                if (cached != null && hash.equals(cached.sha256()) && cached.module().equals(module)) {
                    next.put(key, new SourceFile(cached.root(), cached.path(), module, sf.size(), sf.mtime(),
                            hash, cached.packageName(), cached.imports(), cached.types(), cached.parseError(),
                            cached.properties(), cached.tables()));
                    continue;
                }
                next.put(key, build(root.name(), sf, module, hash, bytes));
                reparsed++;
            }
        }
        int removed = 0;
        for (String key : files.keySet()) {
            if (!next.containsKey(key)) {
                removed++;
            }
        }
        boolean changed = reparsed > 0 || removed > 0 || !nextModules.equals(modules)
                || next.size() != files.size() || !sameMtimes(next);
        files = next;
        modules = nextModules;
        if (changed) {
            saveCache();
        }
        int java = 0;
        int errors = 0;
        for (SourceFile f : files.values()) {
            if (f.isJava()) {
                java++;
                if (f.parseError() != null) {
                    errors++;
                }
            }
        }
        lastStats = new Stats(files.size(), java, reparsed, removed, errors,
                (System.nanoTime() - start) / 1_000_000, changed || lastStats == null);
        return lastStats;
    }

    private boolean sameMtimes(Map<String, SourceFile> next) {
        for (Map.Entry<String, SourceFile> e : next.entrySet()) {
            SourceFile old = files.get(e.getKey());
            if (old == null || old.mtime() != e.getValue().mtime()) {
                return false;
            }
        }
        return true;
    }

    private SourceFile build(String rootName, RepoScanner.ScannedFile sf, String module, String hash, byte[] bytes) {
        String rel = sf.relative();
        if (sf.size() > MAX_PARSE_BYTES) {
            return new SourceFile(rootName, rel, module, sf.size(), sf.mtime(), hash, "", List.of(), List.of(),
                    rel.endsWith(".java") ? "skipped: larger than " + (MAX_PARSE_BYTES / 1024) + " KB" : null,
                    Map.of(), List.of());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!rel.endsWith(".java")) {
            Map<String, String> props = ConfigFileParser.isSpringConfig(rel) ? ConfigFileParser.properties(rel, text) : Map.of();
            List<String> tables = ConfigFileParser.isMigration(rel) ? ConfigFileParser.tables(rel, text) : List.of();
            return new SourceFile(rootName, rel, module, sf.size(), sf.mtime(), hash, "", List.of(), List.of(), null,
                    props, tables);
        }
        JavaFileParser.Parsed parsed = parser.parse(text);
        return new SourceFile(rootName, rel, module, sf.size(), sf.mtime(), hash,
                parsed.packageName(), parsed.imports(), parsed.types(), parsed.error(), Map.of(), List.of());
    }

    private void loadCache() {
        if (!Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            CacheFile cache = JSON.readValue(cacheFile.toFile(), CacheFile.class);
            if (cache.version() != CACHE_VERSION || cache.files() == null) {
                return;
            }
            Map<String, SourceFile> loaded = new HashMap<>();
            for (SourceFile f : cache.files()) {
                loaded.put(f.key(), f);
            }
            files = new LinkedHashMap<>(loaded);
            modules = cache.modules() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cache.modules());
        } catch (IOException e) {
            // A corrupt or foreign cache is discarded: the next refresh rebuilds it from source.
            files = new LinkedHashMap<>();
        }
    }

    private void saveCache() throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Path tmp = cacheFile.resolveSibling("index.json.tmp");
        JSON.writeValue(tmp.toFile(), new CacheFile(CACHE_VERSION, new ArrayList<>(files.values()), modules));
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
