package dev.agentideate.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One complete version of a plan, with what validation found when it was checked against the code. */
public final class PlanVersion {

    public int number;
    public long created;
    public String understanding;
    /** The developer feedback this version answers ({@code null} for the first version). */
    public String feedback;
    public List<PlanChange> changes = new ArrayList<>();

    /** Problems that block approval. */
    public List<String> errors = new ArrayList<>();
    /** Things the developer should see but that do not block approval. */
    public List<String> warnings = new ArrayList<>();
    /** Content hash of every existing file the version touches, when it was validated. */
    public Map<String, String> fileHashes = new LinkedHashMap<>();
    /** Hash of the understanding and changes: what an approval refers to. */
    public String contentHash;
    /** Per change id, against the previous version: {@code new}, {@code changed} or {@code same}. */
    public Map<String, String> changeStatus = new LinkedHashMap<>();
    /** Changes of the previous version this one dropped, as {@code "c3 edit path"}. */
    public List<String> removed = new ArrayList<>();
    public long validated;
}
