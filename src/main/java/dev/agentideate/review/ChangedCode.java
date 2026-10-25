package dev.agentideate.review;

import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.MethodDecl;
import dev.agentideate.index.SourceFile;
import dev.agentideate.plan.PlanChange;
import dev.agentideate.plan.PlanVersion;
import dev.agentideate.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a plan's changes are in the files as they are now, and which methods they fall in. Located
 * from the files themselves (the new text of an edit, the whole of an added file), never from what
 * the plan claims, so review and test generation look at what is really there.
 */
public final class ChangedCode {

    /**
     * One change's new text in today's file.
     *
     * @param file  the indexed file, or {@code null} when it is not indexed (not Java, or gone)
     * @param start first line, or 0 when the new text is not in the file (or the change only removes lines)
     * @param lines the region's current text
     */
    public record Region(PlanChange change, SourceFile file, String display, int start, int end, List<String> lines) {
        public boolean located() {
            return start > 0;
        }
    }

    /** A method a change falls in (or, for an added class, one of its methods). */
    public record Target(TypeNode owner, MethodDecl method, String change) {
    }

    private ChangedCode() {
    }

    public static List<Region> regions(Workspace ws, CodeGraph graph, PlanVersion v) {
        Map<String, SourceFile> byKey = new LinkedHashMap<>();
        graph.files().forEach(f -> byKey.put(f.key(), f));
        List<Region> out = new ArrayList<>();
        for (PlanChange c : v.changes) {
            if (c.root == null || c.path == null || "delete".equals(c.kind)) {
                continue;
            }
            SourceFile f = byKey.get(c.root + ":" + c.path);
            String display = ws.multiRoot() ? c.root + "/" + c.path : c.path;
            Workspace.Root root = ws.root(c.root);
            String content = root == null ? null : read(root.path().resolve(c.path));
            if (content == null) {
                out.add(new Region(c, f, display, 0, 0, List.of()));
                continue;
            }
            List<String> all = content.lines().toList();
            if ("add".equals(c.kind)) {
                out.add(new Region(c, f, display, all.isEmpty() ? 0 : 1, all.size(), all));
                continue;
            }
            String after = c.after == null ? "" : c.after.replace("\r\n", "\n");
            int at = after.isEmpty() ? -1 : nearest(content, after, c.startLine);
            if (at < 0) {
                out.add(new Region(c, f, display, 0, 0, List.of()));
                continue;
            }
            int start = lineOf(content, at);
            int end = start + (int) after.chars().filter(ch -> ch == '\n').count() - (after.endsWith("\n") ? 1 : 0);
            out.add(new Region(c, f, display, start, end, all.subList(start - 1, Math.min(end, all.size()))));
        }
        return out;
    }

    /**
     * The methods each located region overlaps. An added class contributes all its methods; a
     * region outside any method (a field, an import) contributes none.
     */
    public static List<Target> methods(CodeGraph graph, List<Region> regions) {
        List<Target> out = new ArrayList<>();
        for (Region r : regions) {
            if (!r.located() || r.file() == null) {
                continue;
            }
            for (TypeNode t : graph.types()) {
                if (t.file() != r.file()) {
                    continue;
                }
                for (MethodDecl m : t.decl().methods()) {
                    boolean overlaps = m.line() <= r.end() && m.endLine() >= r.start();
                    if ((overlaps || "add".equals(r.change().kind))
                            && out.stream().noneMatch(x -> x.owner() == t && x.method() == m)) {
                        out.add(new Target(t, m, r.change().id));
                    }
                }
            }
        }
        return out;
    }

    /** The occurrence of {@code text} closest to {@code nearLine}, or -1. */
    private static int nearest(String content, String text, int nearLine) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = content.indexOf(text); i >= 0; i = content.indexOf(text, i + 1)) {
            int d = Math.abs(lineOf(content, i) - nearLine);
            if (d < bestDistance) {
                best = i;
                bestDistance = d;
            }
        }
        return best;
    }

    static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The file's text with LF line endings, or {@code null} when it cannot be read. */
    static String read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
        } catch (IOException e) {
            return null;
        }
    }
}
