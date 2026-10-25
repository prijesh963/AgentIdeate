package dev.agentideate.bench;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.tools.ToolRegistry;
import dev.agentideate.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures agentideate on a set of tasks: for each, the answer it gives, whether that answer holds
 * the facts it must, and how much an agent without it would have to read to learn the same.
 *
 * <p>"Files to read" is deliberately a lower bound: only the files that contain the answer, each
 * read once, with no searching and no wrong turns. Real agents read more, so the measured saving is
 * the least saving, not a typical one. The files a task names must exist, so a task file cannot
 * inflate the comparison by naming files that are not there.
 */
public final class Benchmark {

    /**
     * One task.
     *
     * @param question what a developer would ask, for the report
     * @param tool     the agentideate tool that answers it, with its arguments
     * @param expect   text the answer must contain to count as correct
     * @param files    files (relative to a workspace root) holding the answer: what reading would cost
     */
    public record Task(String id, String question, String tool, ObjectNode args, List<String> expect, List<String> files) {
    }

    public record Result(Task task, int answerChars, long fileChars, List<String> missingFacts, List<String> missingFiles,
                         boolean error, String errorText) {

        public boolean passed() {
            return !error && missingFacts.isEmpty() && missingFiles.isEmpty();
        }
    }

    record TaskFile(List<Task> tasks) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Benchmark() {
    }

    public static List<Task> load(Path file) throws IOException {
        TaskFile tf = JSON.readValue(file.toFile(), TaskFile.class);
        if (tf.tasks() == null || tf.tasks().isEmpty()) {
            throw new IOException("No tasks in " + file);
        }
        List<Task> out = new ArrayList<>();
        for (Task t : tf.tasks()) {
            if (t.tool() == null || t.id() == null) {
                throw new IOException("Every task needs an id and a tool: " + t);
            }
            out.add(new Task(t.id(), t.question() == null ? t.id() : t.question(), t.tool(),
                    t.args() == null ? JSON.createObjectNode() : t.args(),
                    t.expect() == null ? List.of() : t.expect(), t.files() == null ? List.of() : t.files()));
        }
        return out;
    }

    /** Runs each task as a fresh question (its own thread), so no answer is shortened by an earlier one. */
    public static List<Result> run(ToolRegistry registry, Workspace workspace, List<Task> tasks) {
        List<Result> results = new ArrayList<>();
        for (Task t : tasks) {
            ObjectNode args = t.args().deepCopy();
            if (!args.has("thread")) {
                args.put("thread", "new");
            }
            ToolRegistry.Outcome o = registry.call(t.tool(), args);
            List<String> missingFacts = new ArrayList<>();
            for (String fact : t.expect()) {
                if (!o.text().contains(fact)) {
                    missingFacts.add(fact);
                }
            }
            long fileChars = 0;
            List<String> missingFiles = new ArrayList<>();
            for (String f : t.files()) {
                Path p = locate(workspace, f);
                if (p == null) {
                    missingFiles.add(f);
                    continue;
                }
                try {
                    fileChars += Files.size(p);
                } catch (IOException e) {
                    missingFiles.add(f);
                }
            }
            results.add(new Result(t, o.text().length(), fileChars, missingFacts, missingFiles, o.error(),
                    o.error() ? o.text() : null));
        }
        return results;
    }

    private static Path locate(Workspace ws, String file) {
        for (Workspace.Root r : ws.roots()) {
            Path p = r.path().resolve(file).normalize();
            if (p.startsWith(r.path()) && Files.isRegularFile(p)) {
                return p;
            }
            if (ws.multiRoot() && file.startsWith(r.name() + "/")) {
                Path q = r.path().resolve(file.substring(r.name().length() + 1)).normalize();
                if (q.startsWith(r.path()) && Files.isRegularFile(q)) {
                    return q;
                }
            }
        }
        return null;
    }

    public static String render(List<Result> results, String workspaceLabel, int toolListChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("agentideate benchmark: ").append(results.size()).append(" tasks on ").append(workspaceLabel).append("\n\n");
        sb.append(String.format(" %-3s %-52s %-15s %7s %16s %6s  %s%n", "#", "question", "tool", "answer", "files to read", "saved", "facts"));
        long answers = 0;
        long files = 0;
        int fileCount = 0;
        int calls = 0;
        int facts = 0;
        int factsFound = 0;
        int i = 0;
        for (Result r : results) {
            i++;
            int expected = r.task().expect().size();
            int found = expected - r.missingFacts().size();
            facts += expected;
            factsFound += r.error() ? 0 : found;
            String saved = r.fileChars() == 0 ? "-" : percent(1.0 - (double) r.answerChars() / r.fileChars());
            String filesCol = r.fileChars() == 0 ? "-" : tokens(r.fileChars()) + " (" + r.task().files().size() + ")";
            sb.append(String.format(" %-3d %-52s %-15s %7s %16s %6s  %s%n", i, clip(r.task().question(), 52), r.task().tool(),
                    tokens(r.answerChars()), filesCol, saved, r.error() ? "ERROR" : found + "/" + expected));
            if (r.fileChars() > 0) {
                answers += r.answerChars();
                files += r.fileChars();
                fileCount += r.task().files().size();
                calls++;
            }
        }
        sb.append('\n');
        sb.append("Total (tasks with files to read): answers ").append(tokens(answers)).append(" tokens vs ").append(tokens(files))
                .append(" tokens of files: ").append(files == 0 ? "-" : percent(1.0 - (double) answers / files))
                .append(" fewer. Facts found: ").append(factsFound).append('/').append(facts).append(".\n");
        sb.append("Tool calls: ").append(calls).append(" (one per task) instead of at least ").append(fileCount)
                .append(" file reads. Files here average ").append(fileCount == 0 ? "-" : tokens(files / fileCount))
                .append(" tokens; larger files mean larger savings.\n");
        sb.append("Fixed cost: agentideate's tool definitions add ~").append(tokens(toolListChars))
                .append(" tokens to every agent request.\n");
        for (Result r : results) {
            if (r.error()) {
                sb.append("ERROR in ").append(r.task().id()).append(": ").append(r.errorText().lines().findFirst().orElse("")).append('\n');
            }
            for (String f : r.missingFacts()) {
                sb.append("MISSING in ").append(r.task().id()).append(": expected \"").append(f).append("\"\n");
            }
            for (String f : r.missingFiles()) {
                sb.append("NO SUCH FILE in ").append(r.task().id()).append(": ").append(f).append('\n');
            }
        }
        sb.append("\"files to read\" counts only the files that hold the answer, each read once, with no searching and no\n")
                .append("wrong turns: the least an agent without agentideate must read. Tokens are estimated at 4 characters.\n");
        return sb.toString();
    }

    static String tokens(long chars) {
        long t = Math.max(1, chars / 4);
        return t >= 1000 ? String.format("%.1fk", t / 1000.0) : Long.toString(t);
    }

    static String percent(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }
}
