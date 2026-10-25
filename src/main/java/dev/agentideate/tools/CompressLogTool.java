package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.Workspace;
import dev.agentideate.workspace.WorkspaceService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reduces build and test output to what failed and where. Reads, in order of preference: a log
 * file inside the workspace ({@code path}), pasted text ({@code text}), or, with neither, the
 * JUnit XML reports Gradle and Maven leave under {@code build/test-results} and
 * {@code target/surefire-reports}/{@code failsafe-reports}. Passing a path is cheapest: the log
 * never travels through the agent's context.
 */
final class CompressLogTool implements Tool {

    /** A larger log is read from its end, where the failure summary is. */
    static final long MAX_LOG_BYTES = 20L * 1024 * 1024;

    @Override
    public String name() {
        return "compress_log";
    }

    @Override
    public String description() {
        return "Summarize a Gradle/Maven build, test or Spring Boot startup failure to errors with file:line. "
                + "Pass a log file path (cheapest) or text; with neither, reads the JUnit XML test reports.";
    }

    @Override
    public boolean threaded() {
        return false;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ObjectNode p = (ObjectNode) s.get("properties");
        p.putObject("path").put("type", "string").put("description", "Log file inside the project, e.g. build/test.log");
        p.putObject("text").put("type", "string").put("description", "Log text, when there is no file");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException {
        Workspace ws = snapshot.workspace();
        LogDigest digest = new LogDigest(classLine -> locate(graph, out, classLine), abs -> display(ws, graph, out, abs));
        String path = args.string("path");
        String text = args.string("text");
        if (path != null) {
            Path file = insideWorkspace(ws, path);
            String log = read(file);
            out.input(log.length(), "a " + kb(log.length()) + " log");
            digest.digest(log).forEach(out::line);
        } else if (text != null) {
            out.input(text.length(), "a " + kb(text.length()) + " log");
            digest.digest(text).forEach(out::line);
        } else {
            junitReports(snapshot, graph, digest, out);
        }
    }

    /** A class (and line) in a stack frame, as {@code path:line} when the class is in the workspace. */
    private static String locate(CodeGraph graph, Answer out, String classLine) {
        int colon = classLine.lastIndexOf(':');
        String cls = classLine.substring(0, colon).replaceAll("\\$.*", "");
        String line = classLine.substring(colon + 1);
        List<TypeNode> nodes = graph.byFqn(cls);
        if (nodes.isEmpty()) {
            return null;
        }
        TypeNode n = nodes.get(0);
        int ln = Integer.parseInt(line);
        return ln > 0 ? out.ref(n.file(), ln) : out.ref(n.file());
    }

    /**
     * A path from a compile error as the workspace shows it: inside a root, relative to it; else the
     * indexed file whose path the logged one ends with (a log from CI or another machine); else as
     * written.
     */
    private static String display(Workspace ws, CodeGraph graph, Answer out, String absolute) {
        String normalized = absolute.replace('\\', '/');
        try {
            Path p = Path.of(absolute).toAbsolutePath().normalize();
            for (Workspace.Root r : ws.roots()) {
                if (p.startsWith(r.path())) {
                    String rel = r.path().relativize(p).toString().replace('\\', '/');
                    for (var f : graph.files()) {
                        if (f.root().equals(r.name()) && f.path().equals(rel)) {
                            return out.ref(f);
                        }
                    }
                    return ws.multiRoot() ? r.name() + "/" + rel : rel;
                }
            }
        } catch (RuntimeException ignored) {
            // not a local path; try the suffix match below
        }
        SourceFile best = null;
        for (var f : graph.files()) {
            if (normalized.endsWith("/" + f.path()) && (best == null || f.path().length() > best.path().length())) {
                best = f;
            }
        }
        return best != null ? out.ref(best) : absolute;
    }

    /** Only files inside the workspace are read: the tool must not become a way to read anything on disk. */
    static Path insideWorkspace(Workspace ws, String path) throws ToolException {
        Path p = Path.of(path);
        List<Path> candidates = new ArrayList<>();
        if (p.isAbsolute()) {
            candidates.add(p);
        } else {
            ws.roots().forEach(r -> candidates.add(r.path().resolve(path)));
        }
        for (Path c : candidates) {
            Path n = c.toAbsolutePath().normalize();
            for (Workspace.Root r : ws.roots()) {
                if (n.startsWith(r.path()) && Files.isRegularFile(n)) {
                    try {
                        if (n.toRealPath().startsWith(r.path())) {
                            return n;
                        }
                    } catch (IOException ignored) {
                        // fall through to the error below
                    }
                }
            }
        }
        throw new ToolException("Not found: no file '" + path + "' inside the project folders ("
                + String.join(", ", ws.roots().stream().map(r -> r.path().toString()).toList())
                + "). Only files inside the project are read.");
    }

    private static String read(Path file) throws ToolException {
        try {
            long size = Files.size(file);
            if (size <= MAX_LOG_BYTES) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                byte[] tail = new byte[(int) MAX_LOG_BYTES];
                raf.seek(size - MAX_LOG_BYTES);
                raf.readFully(tail);
                return new String(tail, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new ToolException("Could not read " + file + ": " + e.getMessage());
        }
    }

    private void junitReports(WorkspaceService.Snapshot snapshot, CodeGraph graph, LogDigest digest, Answer out)
            throws ToolException {
        List<Path> reports = new ArrayList<>();
        for (Workspace.Root r : snapshot.workspace().roots()) {
            List<String> modules = snapshot.graph().files().stream()
                    .filter(f -> f.root().equals(r.name())).map(f -> f.module()).distinct().toList();
            for (String m : modules) {
                Path base = m.isEmpty() ? r.path() : r.path().resolve(m);
                for (String dir : List.of("build/test-results", "target/surefire-reports", "target/failsafe-reports")) {
                    collectXml(base.resolve(dir), reports);
                }
            }
        }
        if (reports.isEmpty()) {
            out.line("No JUnit XML reports found under build/test-results or target/surefire-reports. "
                    + "Run the tests, or pass the build output as path or text.");
            return;
        }
        int tests = 0;
        int failed = 0;
        long newest = 0;
        long bytes = 0;
        List<String> lines = new ArrayList<>();
        DocumentBuilder builder = secureBuilder();
        for (Path report : reports) {
            try {
                bytes += Files.size(report);
                newest = Math.max(newest, Files.getLastModifiedTime(report).toMillis());
                Document doc = builder.parse(report.toFile());
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element tc = (Element) cases.item(i);
                    tests++;
                    Element problem = first(tc, "failure");
                    String kind = "FAIL";
                    if (problem == null) {
                        problem = first(tc, "error");
                        kind = "ERROR";
                    }
                    if (problem == null) {
                        continue;
                    }
                    failed++;
                    String message = problem.getAttribute("message");
                    if (message.isBlank()) {
                        message = problem.getAttribute("type");
                    }
                    List<String> trace = digest.digest(problem.getTextContent());
                    String at = trace.stream().filter(l -> l.trim().startsWith("at ")).findFirst().map(String::trim).orElse("");
                    lines.add("  " + kind + " " + LogDigest.simpleName(tc.getAttribute("classname")) + "." + tc.getAttribute("name")
                            + "  " + LogDigest.shorten(message.lines().findFirst().orElse("")) + (at.isEmpty() ? "" : "  " + at));
                }
            } catch (Exception e) {
                lines.add("  (unreadable report " + report.getFileName() + ": " + e.getMessage() + ")");
            }
        }
        out.input(bytes, reports.size() + " JUnit report" + (reports.size() == 1 ? "" : "s"));
        long ageMin = (System.currentTimeMillis() - newest) / 60_000;
        out.line("JUnit reports: " + reports.size() + " files, " + tests + " tests, " + failed + " failed"
                + " (newest written " + (ageMin < 1 ? "just now" : ageMin + " min ago") + "; older reports may predate your last change)");
        lines.stream().limit(LogDigest.MAX_ITEMS).forEach(out::line);
        if (lines.size() > LogDigest.MAX_ITEMS) {
            out.line("  +" + (lines.size() - LogDigest.MAX_ITEMS) + " more");
        }
    }

    private static void collectXml(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir, 4)) {
            s.filter(p -> p.getFileName().toString().endsWith(".xml") && p.getFileName().toString().startsWith("TEST-"))
                    .forEach(out::add);
        } catch (IOException ignored) {
            // an unreadable report folder is skipped
        }
    }

    /** XML parsing with DTDs and external entities off: a report file must not be able to read other files. */
    static DocumentBuilder secureBuilder() throws ToolException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder();
        } catch (Exception e) {
            throw new ToolException("XML parser unavailable: " + e.getMessage());
        }
    }

    private static Element first(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    static String kb(long chars) {
        return chars < 1024 ? chars + " B" : String.format("%.1f KB", chars / 1024.0);
    }
}
