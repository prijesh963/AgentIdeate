package dev.agentideate.implement;

import java.util.ArrayList;
import java.util.List;

/**
 * One application of an approved plan: what was written where, with the hashes needed to verify
 * it and to undo it safely. Kept in {@code runs/<id>/run.json}, next to the backups.
 */
public final class RunRecord {

    public static final class FileRecord {
        public String change;
        public String kind;
        public String root;
        public String path;
        /** Hash before the run ({@code null} for an added file). */
        public String originalHash;
        /** Hash the run wrote ({@code null} for a deleted file). */
        public String writtenHash;
        /** The original's copy inside the run folder ({@code null} for an added file). */
        public String backup;
    }

    public String id;
    public String plan;
    public int version;
    public long created;
    public String via;
    /** {@code applied}, {@code rolled_back} or {@code refused}. */
    public String status;
    public List<FileRecord> files = new ArrayList<>();
    /** Folders the run created for new files, removed again on undo when empty. */
    public List<String> createdDirs = new ArrayList<>();
    public List<String> verify = new ArrayList<>();
    public List<String> outsidePlan = new ArrayList<>();
    public String compileStatus;
    public String compileCommand;
    public List<String> compileDigest = new ArrayList<>();
    public String note;
}
