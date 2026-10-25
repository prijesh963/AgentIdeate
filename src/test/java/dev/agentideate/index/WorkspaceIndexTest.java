package dev.agentideate.index;

import dev.agentideate.TestSupport;
import dev.agentideate.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceIndexTest {

    @TempDir
    Path tmp;

    private Path repo() throws Exception {
        return TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
    }

    @Test
    void fullIndexFindsJavaAndConfigFilesAndModules() throws Exception {
        WorkspaceIndex index = new WorkspaceIndex(Workspace.of(List.of(repo())), tmp.resolve("state"));
        WorkspaceIndex.Stats s = index.refresh();
        assertEquals(27, s.javaFiles());
        assertEquals(s.files(), s.reparsed());
        assertEquals(0, s.parseErrors());
        assertTrue(s.changed());
        assertEquals(List.of("inventory-service", "order-service", "pricing-service"),
                index.modules().get("shop").stream().sorted().toList());
        assertTrue(index.files().stream().anyMatch(f -> f.path().endsWith("application.yml")));
        assertTrue(index.files().stream().noneMatch(f -> f.path().endsWith("README.md")));
    }

    @Test
    void secondRefreshWithNoChangesReparsesNothing() throws Exception {
        WorkspaceIndex index = new WorkspaceIndex(Workspace.of(List.of(repo())), tmp.resolve("state"));
        index.refresh();
        WorkspaceIndex.Stats s = index.refresh();
        assertEquals(0, s.reparsed());
        assertFalse(s.changed());
    }

    @Test
    void anEditedFileIsReparsedAndOnlyThatFile() throws Exception {
        Path repo = repo();
        WorkspaceIndex index = new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state"));
        index.refresh();
        Path svc = repo.resolve("pricing-service/src/main/java/com/shop/pricing/service/PriceService.java");
        Files.writeString(svc, Files.readString(svc).replace("currentPrice", "latestPrice"));
        WorkspaceIndex.Stats s = index.refresh();
        assertEquals(1, s.reparsed());
        assertTrue(s.changed());
        SourceFile f = index.files().stream().filter(x -> x.path().endsWith("PriceService.java")).findFirst().orElseThrow();
        assertTrue(f.types().get(0).methods().stream().anyMatch(m -> m.name().equals("latestPrice")));
    }

    @Test
    void aTouchedButUnchangedFileIsNotReparsed() throws Exception {
        Path repo = repo();
        WorkspaceIndex index = new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state"));
        index.refresh();
        Path svc = repo.resolve("pricing-service/src/main/java/com/shop/pricing/service/PriceService.java");
        Files.setLastModifiedTime(svc, FileTime.fromMillis(Files.getLastModifiedTime(svc).toMillis() + 60_000));
        assertEquals(0, index.refresh().reparsed());
    }

    @Test
    void deletedAndAddedFilesAreNoticed() throws Exception {
        Path repo = repo();
        WorkspaceIndex index = new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state"));
        index.refresh();
        Files.delete(repo.resolve("order-service/src/main/java/com/shop/order/client/PriceResponse.java"));
        Files.writeString(repo.resolve("order-service/src/main/java/com/shop/order/New.java"), "package com.shop.order; class New {}");
        WorkspaceIndex.Stats s = index.refresh();
        assertEquals(1, s.removed());
        assertEquals(1, s.reparsed());
        assertEquals(27, s.javaFiles());
    }

    @Test
    void theCacheOnDiskIsReusedByANewProcess() throws Exception {
        Path repo = repo();
        new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state")).refresh();
        assertTrue(Files.isRegularFile(tmp.resolve("state/index.json")));
        WorkspaceIndex.Stats s = new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state")).refresh();
        assertEquals(0, s.reparsed());
        assertEquals(27, s.javaFiles());
    }

    @Test
    void aCorruptCacheIsRebuiltNotTrusted() throws Exception {
        Path repo = repo();
        Files.createDirectories(tmp.resolve("state"));
        Files.writeString(tmp.resolve("state/index.json"), "{not json");
        WorkspaceIndex.Stats s = new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state")).refresh();
        assertEquals(s.files(), s.reparsed());
    }

    @Test
    void buildOutputAndVcsFoldersAreSkipped() throws Exception {
        Path repo = repo();
        Files.createDirectories(repo.resolve("order-service/build/generated"));
        Files.writeString(repo.resolve("order-service/build/generated/Gen.java"), "class Gen {}");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/config.xml"), "<x/>");
        WorkspaceIndex index = new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state"));
        assertEquals(27, index.refresh().javaFiles());
        assertTrue(index.files().stream().noneMatch(f -> f.path().startsWith(".git")));
    }

    @Test
    void anUnparseableFileIsCountedNotFatal() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("order-service/src/main/java/Broken.java"), "class { nope");
        WorkspaceIndex.Stats s = new WorkspaceIndex(Workspace.of(List.of(repo)), tmp.resolve("state")).refresh();
        assertEquals(1, s.parseErrors());
    }
}
