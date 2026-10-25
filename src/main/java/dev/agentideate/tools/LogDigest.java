package dev.agentideate.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Gradle or Maven build log (compile, test, Spring Boot startup) into the few lines that
 * matter: the outcome, each compile error once, each failing test with its message, and each
 * exception with its cause chain and only the stack frames in the workspace's own code.
 *
 * <p>Frames are mapped to files by a locator (class name to {@code path:line}, or {@code null} for
 * a class outside the index); everything else, framework and JDK frames, is counted, not shown.
 */
public final class LogDigest {

    static final int MAX_PROJECT_FRAMES = 3;
    static final int MAX_ITEMS = 25;

    private static final Pattern GRADLE_JAVAC = Pattern.compile("^(.+\\.java):(\\d+): error: (.*)$");
    private static final Pattern MAVEN_JAVAC = Pattern.compile("^\\[ERROR] (.+\\.java):\\[(\\d+)(?:,\\d+)?] (.*)$");
    private static final Pattern GRADLE_TEST = Pattern.compile("^(\\S+) > (.+?) FAILED$");
    private static final Pattern MAVEN_TEST = Pattern.compile("^\\[ERROR]\\s+(?:Run \\d+: )?([\\w$.]+)\\.([\\w$]+)(?::(\\d+))?\\s+(.*)$");
    private static final Pattern EXCEPTION = Pattern.compile(
            "^\\s*(Caused by: |Suppressed: )?((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable|Failure|Failed)[\\w$]*)(?::\\s*(.*))?$");
    /** {@code at a.b.C.m(C.java:42)}, also with a classloader/module prefix ({@code at app//...}, {@code at java.base/...}). */
    private static final Pattern FRAME = Pattern.compile("^\\s*at (?:[\\w.$-]+/+)?([\\w$.]+)\\.([\\w$<>]+)\\(([^:)]*)(?::(\\d+))?\\)");
    private static final Pattern TIMESTAMPED = Pattern.compile("^\\d{4}-\\d\\d-\\d\\d[T ]\\d\\d:\\d\\d");
    private static final Pattern MORE_FRAMES = Pattern.compile("^\\s*\\.\\.\\. \\d+ more$");

    private final Function<String, String> locate;
    private final Function<String, String> displayPath;
    /** Lines already reported as part of a test failure, so its trace is not reported again. */
    private boolean[] consumed = new boolean[0];

    /**
     * @param locate      a class name and line ({@code a.b.C:42}) to {@code path:line}, or {@code null}
     *                    when the class is not in the workspace
     * @param displayPath an absolute path from a compile error to how the workspace shows it
     */
    public LogDigest(Function<String, String> locate, Function<String, String> displayPath) {
        this.locate = locate;
        this.displayPath = displayPath;
    }

    public List<String> digest(String log) {
        String[] lines = log.replace("\r\n", "\n").split("\n");
        consumed = new boolean[lines.length];
        List<String> out = new ArrayList<>();
        outcome(lines, out);
        compileErrors(lines, out);
        tests(lines, out);
        springBoot(lines, out);
        exceptions(lines, out);
        if (out.isEmpty()) {
            out.add("Nothing recognized: no build outcome, compile error, test failure or exception in " + lines.length + " lines.");
        }
        return out;
    }

    private void outcome(String[] lines, List<String> out) {
        String outcome = null;
        for (int i = 0; i < lines.length; i++) {
            String l = strip(lines[i]);
            if (l.equals("BUILD SUCCESSFUL") || l.startsWith("BUILD SUCCESSFUL in") || l.equals("[INFO] BUILD SUCCESS")) {
                outcome = "Build: SUCCESS";
            } else if (l.startsWith("BUILD FAILED") || l.equals("[INFO] BUILD FAILURE") || l.startsWith("FAILURE: Build failed")) {
                outcome = "Build: FAILED";
            }
            if (l.equals("* What went wrong:")) {
                List<String> what = new ArrayList<>();
                for (int j = i + 1; j < lines.length && what.size() < 6; j++) {
                    String w = strip(lines[j]);
                    if (w.startsWith("* Try:") || w.startsWith("* Exception is:") || w.startsWith("BUILD ")) {
                        break;
                    }
                    if (!w.isBlank()) {
                        what.add("  " + w.trim());
                    }
                }
                out.add("What went wrong (Gradle):");
                out.addAll(what);
            }
        }
        if (outcome != null) {
            out.add(0, outcome);
        }
    }

    private void compileErrors(String[] lines, List<String> out) {
        Map<String, Integer> errors = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String l = strip(lines[i]);
            Matcher g = GRADLE_JAVAC.matcher(l);
            Matcher m = MAVEN_JAVAC.matcher(l);
            String file;
            String line;
            String msg;
            if (g.matches()) {
                file = g.group(1);
                line = g.group(2);
                msg = g.group(3);
            } else if (m.matches()) {
                file = m.group(1);
                line = m.group(2);
                msg = m.group(3);
            } else {
                continue;
            }
            StringBuilder detail = new StringBuilder(msg.trim());
            for (int j = i + 1; j < lines.length && j <= i + 6; j++) {
                String raw = strip(lines[j]);
                if (GRADLE_JAVAC.matcher(raw).matches() || MAVEN_JAVAC.matcher(raw).matches()) {
                    break; // the next error's details are its own
                }
                String next = raw.replaceFirst("^\\[ERROR]", "").trim();
                if (next.startsWith("symbol:") || next.startsWith("location:") || next.startsWith("required:")
                        || next.startsWith("found:") || next.startsWith("reason:")) {
                    detail.append("; ").append(next.replaceAll("\\s+", " "));
                }
            }
            errors.merge("  " + displayPath.apply(file.trim()) + ":" + line + "  " + detail, 1, Integer::sum);
        }
        if (!errors.isEmpty()) {
            out.add("Compile errors (" + errors.size() + "):");
            errors.entrySet().stream().limit(MAX_ITEMS)
                    .forEach(e -> out.add(e.getKey() + (e.getValue() > 1 ? "  (x" + e.getValue() + ")" : "")));
            more(out, errors.size());
        }
    }

    private void tests(String[] lines, List<String> out) {
        List<String> failures = new ArrayList<>();
        String summary = null;
        for (int i = 0; i < lines.length; i++) {
            String l = strip(lines[i]);
            Matcher g = GRADLE_TEST.matcher(l.trim());
            if (g.matches()) {
                String message = null;
                List<String> frames = new ArrayList<>();
                for (int j = i + 1; j < lines.length; j++) {
                    String n = strip(lines[j]);
                    if (!n.startsWith(" ") && !n.startsWith("\t") || n.isBlank()) {
                        break;
                    }
                    consumed[j] = true;
                    Matcher f = FRAME.matcher(n);
                    if (f.find()) {
                        String where = frame(f);
                        if (where != null && frames.size() < MAX_PROJECT_FRAMES) {
                            frames.add(where);
                        }
                    } else if (message == null) {
                        message = n.trim();
                    }
                }
                failures.add("  " + g.group(1) + " > " + g.group(2) + (message == null ? "" : "  " + shorten(message))
                        + (frames.isEmpty() ? "" : "  at " + String.join(" < ", frames)));
                continue;
            }
            Matcher m = MAVEN_TEST.matcher(l);
            if (m.matches() && !l.contains("Tests run:") && !l.contains("Failures:") && !l.contains(".java:[")
                    && !l.contains("Time elapsed") && !l.contains("<<<")
                    && Character.isUpperCase(simpleName(m.group(1)).charAt(0))) {
                String where = m.group(3) == null ? null : locate.apply(m.group(1) + ":" + m.group(3));
                failures.add("  " + simpleName(m.group(1)) + "." + m.group(2) + "  " + shorten(m.group(4))
                        + (where == null ? "" : "  at " + where));
            }
            if (l.matches(".*\\d+ tests? completed, \\d+ failed.*") || l.matches("^\\[(ERROR|WARNING)] Tests run: \\d+, Failures: \\d+, Errors: \\d+.*")
                    || l.matches("^Tests run: \\d+, Failures: \\d+, Errors: \\d+.*")) {
                summary = l.replaceFirst("^\\[(ERROR|WARNING)] ", "").trim();
            }
        }
        if (!failures.isEmpty() || summary != null) {
            out.add("Test failures (" + failures.size() + ")" + (summary == null ? ":" : ": " + summary));
            failures.stream().distinct().limit(MAX_ITEMS).forEach(out::add);
            more(out, failures.size());
        }
    }

    private void springBoot(String[] lines, List<String> out) {
        for (int i = 0; i < lines.length; i++) {
            if (!strip(lines[i]).contains("APPLICATION FAILED TO START")) {
                continue;
            }
            out.add("Spring Boot: APPLICATION FAILED TO START");
            boolean inAction = false;
            boolean actionText = false;
            int kept = 0;
            for (int j = i + 1; j < lines.length && kept < 14; j++) {
                String raw = lines[j];
                if (TIMESTAMPED.matcher(raw).find()) {
                    break; // the report is over; what follows is ordinary log output
                }
                String l = strip(raw).trim();
                if (l.isEmpty() || l.matches("\\*+")) {
                    if (actionText) {
                        break; // the Action paragraph ended
                    }
                    continue;
                }
                if (l.startsWith("Action:")) {
                    inAction = true;
                } else if (inAction) {
                    actionText = true;
                }
                out.add("  " + shorten(l));
                kept++;
            }
            return;
        }
    }

    private record Trace(String header, List<String> causes, List<String> frames, int hidden) {
        String key() {
            return header + "|" + causes + "|" + (frames.isEmpty() ? "" : frames.get(0));
        }
    }

    private void exceptions(String[] lines, List<String> out) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Trace> traces = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            if (consumed[i]) {
                continue;
            }
            Matcher e = EXCEPTION.matcher(strip(lines[i]));
            if (!e.matches() || e.group(1) != null || !nextIsFrame(lines, i)) {
                continue; // a trace starts at an exception line followed by frames, not at a "Caused by"
            }
            String header = simpleName(e.group(2)) + (e.group(3) == null ? "" : ": " + shorten(e.group(3)));
            List<String> causes = new ArrayList<>();
            List<String> frames = new ArrayList<>();
            int hidden = 0;
            int j = i + 1;
            for (; j < lines.length; j++) {
                String l = strip(lines[j]);
                Matcher f = FRAME.matcher(l);
                Matcher c = EXCEPTION.matcher(l);
                if (f.find()) {
                    String where = frame(f);
                    if (where != null && frames.size() < MAX_PROJECT_FRAMES) {
                        frames.add(where);
                    } else {
                        hidden++;
                    }
                } else if (c.matches() && c.group(1) != null) {
                    causes.add(simpleName(c.group(2)) + (c.group(3) == null ? "" : ": " + shorten(c.group(3))));
                } else if (!MORE_FRAMES.matcher(l).matches()) {
                    break;
                }
            }
            i = j - 1;
            Trace t = new Trace(header, causes, frames, hidden);
            counts.merge(t.key(), 1, Integer::sum);
            traces.putIfAbsent(t.key(), t);
        }
        if (traces.isEmpty()) {
            return;
        }
        out.add("Exceptions (" + traces.size() + " distinct):");
        traces.values().stream().limit(MAX_ITEMS).forEach(t -> {
            int n = counts.get(t.key());
            out.add("  " + t.header() + (n > 1 ? "  (x" + n + ")" : ""));
            if (!t.causes().isEmpty()) {
                out.add("    root cause: " + t.causes().get(t.causes().size() - 1)
                        + (t.causes().size() > 1 ? "  (" + t.causes().size() + " causes in chain)" : ""));
            }
            out.add("    " + (t.frames().isEmpty() ? "no frame in this workspace's code" : "at " + String.join(" < ", t.frames()))
                    + (t.hidden() > 0 ? "  (+" + t.hidden() + " library frames)" : ""));
        });
        more(out, traces.size());
    }

    private boolean nextIsFrame(String[] lines, int i) {
        return i + 1 < lines.length && FRAME.matcher(strip(lines[i + 1])).find();
    }

    private String frame(Matcher f) {
        String cls = f.group(1);
        String line = f.group(4);
        String where = locate.apply(cls + ":" + (line == null ? "0" : line));
        return where == null ? null : simpleName(cls) + "." + f.group(2) + " " + where;
    }

    /** Gradle/Maven console noise: ANSI colours and timestamps before the content. */
    static String strip(String l) {
        return l.replaceAll("\u001B\\[[;\\d]*m", "").replaceFirst("^\\d{4}-\\d\\d-\\d\\d[T ][\\d:.,]+\\S*\\s+", "");
    }

    static String simpleName(String fqn) {
        String s = fqn.substring(fqn.lastIndexOf('.') + 1);
        return s.isEmpty() ? fqn : s;
    }

    static String shorten(String s) {
        String t = s.trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    private static void more(List<String> out, int count) {
        if (count > MAX_ITEMS) {
            out.add("  +" + (count - MAX_ITEMS) + " more");
        }
    }
}
