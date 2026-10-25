package dev.agentideate.workspace;

import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceServiceTest {

    @TempDir
    Path home;

    @Test
    void repoArgumentAcceptsSeveralFolders() throws Exception {
        WorkspaceService s = new WorkspaceService(new AppHome(home), List.of(), null);
        assertEquals(List.of(Path.of("/a"), Path.of("/b"), Path.of("/c")), s.chooseRoots("/a, /b;/c"));
    }

    @Test
    void configuredRootsBeatClientRootsAndClientRootsBeatTheWorkingDir(@TempDir Path wd) throws Exception {
        Files.createFile(wd.resolve("pom.xml"));
        WorkspaceService s = new WorkspaceService(new AppHome(home), List.of(), wd);
        assertEquals(List.of(wd), s.chooseRoots(null));
        s.setClientRoots(List.of(Path.of("/client")));
        assertEquals(List.of(Path.of("/client")), s.chooseRoots(null));
        WorkspaceService configured = new WorkspaceService(new AppHome(home), List.of(Path.of("/cfg")), wd);
        configured.setClientRoots(List.of(Path.of("/client")));
        assertEquals(List.of(Path.of("/cfg")), configured.chooseRoots(null));
    }

    @Test
    void aWorkingDirThatIsNotAProjectIsNotGuessed(@TempDir Path wd) {
        WorkspaceService s = new WorkspaceService(new AppHome(home), List.of(), wd);
        WorkspaceService.NoWorkspaceException e = assertThrows(WorkspaceService.NoWorkspaceException.class, () -> s.chooseRoots(null));
        assertTrue(e.getMessage().contains("mcp.json"));
    }

    @Test
    void aMissingFolderIsNamed() {
        WorkspaceService s = new WorkspaceService(new AppHome(home), List.of(), null);
        Exception e = assertThrows(WorkspaceService.NoWorkspaceException.class, () -> s.snapshot(home.resolve("nope").toString()));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void theGraphIsReusedUntilSomethingChanges() throws Exception {
        WorkspaceService s = new WorkspaceService(new AppHome(home), List.of(TestSupport.shop()), null);
        var first = s.snapshot(null);
        var second = s.snapshot(null);
        assertSame(first.graph(), second.graph());
        assertTrue(Files.isRegularFile(first.stateDir().resolve("index.json")));
        assertTrue(first.stateDir().startsWith(home), "state stays under the agentideate home, never in the project");
    }

    @Test
    void workspaceIdIsStableAndOrderIndependent() {
        Workspace a = Workspace.of(List.of(Path.of("/x/one"), Path.of("/x/two")));
        Workspace b = Workspace.of(List.of(Path.of("/x/two"), Path.of("/x/one")));
        assertEquals(a.id(), b.id());
        assertNotEquals(a.id(), Workspace.of(List.of(Path.of("/x/one"))).id());
    }

    @Test
    void duplicateRootNamesAreDisambiguated() {
        Workspace w = Workspace.of(List.of(Path.of("/a/svc"), Path.of("/b/svc")));
        assertEquals(List.of("svc", "svc-2"), w.roots().stream().map(Workspace.Root::name).toList());
    }
}
