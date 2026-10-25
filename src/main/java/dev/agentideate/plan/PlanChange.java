package dev.agentideate.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * One change in a plan.
 *
 * <ul>
 *   <li>{@code edit}: replace {@code before} (quoted exactly from the file, matching once) with
 *       {@code after}; an empty {@code after} removes the lines;</li>
 *   <li>{@code add}: create {@code file} with {@code after} as its content;</li>
 *   <li>{@code delete}: remove {@code file}.</li>
 * </ul>
 *
 * The location fields are filled in by validation from the actual file, never taken from the model.
 */
public final class PlanChange {

    public String id;
    public String kind;
    public String file;
    public String reason;
    public String before;
    public String after;
    /** Classes, methods, topics or tables in the index this change is based on. */
    public List<String> evidence = new ArrayList<>();

    // filled in by validation
    public String root;
    public String path;
    public int startLine;
    public int endLine;

    PlanChange copy() {
        PlanChange c = new PlanChange();
        c.id = id;
        c.kind = kind;
        c.file = file;
        c.reason = reason;
        c.before = before;
        c.after = after;
        c.evidence = new ArrayList<>(evidence);
        c.root = root;
        c.path = path;
        c.startLine = startLine;
        c.endLine = endLine;
        return c;
    }

    /** The parts the model wrote, for comparing versions and hashing. */
    String content() {
        return kind + "\u0000" + file + "\u0000" + (before == null ? "" : before) + "\u0000" + (after == null ? "" : after)
                + "\u0000" + (reason == null ? "" : reason);
    }
}
