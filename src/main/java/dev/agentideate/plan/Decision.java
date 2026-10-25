package dev.agentideate.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * A rule the developer set while reviewing ("keep PaymentService as it is", "reuse
 * ApprovalEvent"). Every later version is shown the full list; a decision that names files to keep
 * out of the plan ({@code forbid}, as path globs) is enforced by validation.
 */
public final class Decision {

    public String id;
    public String text;
    public List<String> forbid = new ArrayList<>();
    public int fromVersion;
    public long created;
}
