package dev.agentideate.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the JUnit XML reports Gradle ({@code build/test-results}) and Maven
 * ({@code target/surefire-reports}, {@code failsafe-reports}) write: how many tests ran, and one
 * line per failure with its message and the first frame in the project's own code.
 */
public final class JUnitReports {

    /** @param lines one line per failed test, already indented */
    public record Summary(int reports, int tests, int failed, int skipped, long bytes, long newest, List<String> lines) {
    }

    private JUnitReports() {
    }

    /** {@code TEST-*.xml} files under {@code dir}, written at or after {@code since} (epoch millis; 0 for all). */
    public static void collect(Path dir, long since, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir, 4)) {
            s.filter(p -> p.getFileName().toString().endsWith(".xml") && p.getFileName().toString().startsWith("TEST-"))
                    .filter(p -> since <= 0 || modified(p) >= since)
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) {
            // an unreadable report folder is skipped
        }
    }

    public static Summary read(List<Path> reports, LogDigest digest) throws ToolException {
        int tests = 0;
        int failed = 0;
        int skipped = 0;
        long newest = 0;
        long bytes = 0;
        List<String> lines = new ArrayList<>();
        DocumentBuilder builder = secureBuilder();
        for (Path report : reports) {
            try {
                bytes += Files.size(report);
                newest = Math.max(newest, modified(report));
                Document doc = builder.parse(report.toFile());
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element tc = (Element) cases.item(i);
                    tests++;
                    if (first(tc, "skipped") != null) {
                        skipped++;
                        continue;
                    }
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
        return new Summary(reports.size(), tests, failed, skipped, bytes, newest, lines);
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

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Element first(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }
}
