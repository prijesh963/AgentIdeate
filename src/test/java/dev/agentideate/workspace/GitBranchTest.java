package dev.agentideate.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitBranchTest {

    @TempDir
    Path tmp;

    @Test
    void branchFromHeadDetachedHeadWorktreeAndNoGit() throws Exception {
        Path a = tmp.resolve("a");
        Files.createDirectories(a.resolve(".git"));
        Files.writeString(a.resolve(".git/HEAD"), "ref: refs/heads/feature/invoice\n");
        assertEquals("feature/invoice", Workspace.gitBranch(a));

        Path b = tmp.resolve("b");
        Files.createDirectories(b.resolve(".git"));
        Files.writeString(b.resolve(".git/HEAD"), "0123456789abcdef0123456789abcdef01234567\n");
        assertEquals("0123456789ab", Workspace.gitBranch(b));

        Path wt = tmp.resolve("wt");
        Files.createDirectories(wt);
        Files.createDirectories(tmp.resolve("realgit"));
        Files.writeString(tmp.resolve("realgit/HEAD"), "ref: refs/heads/main\n");
        Files.writeString(wt.resolve(".git"), "gitdir: ../realgit\n");
        assertEquals("main", Workspace.gitBranch(wt));

        assertNull(Workspace.gitBranch(tmp.resolve("none")));
        assertEquals("a=feature/invoice,b=0123456789ab", Workspace.of(List.of(a, b, tmp.resolve("none"))).branch());
    }
}
