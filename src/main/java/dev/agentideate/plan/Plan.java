package dev.agentideate.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned change, from first draft to approval: its versions (each a complete, validated list of
 * changes), the decisions the developer made along the way, and the approval itself. Persisted as
 * JSON in the workspace's state directory; nothing about a plan is written into the project.
 */
public final class Plan {

    public String id;
    public String title;
    public String story;
    public long created;
    public long updated;
    /** {@code draft}, {@code approved} or {@code abandoned}. */
    public String status = "draft";
    public List<PlanVersion> versions = new ArrayList<>();
    public List<Decision> decisions = new ArrayList<>();

    public Integer approvedVersion;
    public long approvedAt;
    /** How approval was given: {@code chat form}, {@code Copilot tool confirmation} or {@code terminal}. */
    public String approvedVia;
    /** The approved version's content hash: implementation refuses anything else. */
    public String approvedHash;

    /** Changes the developer asked for (through the chat form) that the next version must address. */
    public String pendingFeedback;

    /** Next change id; ids are never reused within a plan, so "c4" always means the same change. */
    public int nextChange = 1;
    public int nextDecision = 1;

    public PlanVersion latest() {
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    public PlanVersion version(int n) {
        for (PlanVersion v : versions) {
            if (v.number == n) {
                return v;
            }
        }
        return null;
    }

    public boolean approved() {
        return "approved".equals(status) && approvedVersion != null;
    }
}
