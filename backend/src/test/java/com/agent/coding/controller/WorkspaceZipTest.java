package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests workspace download/upload zip handling: recursive zip packing that
 * skips runtime dirs, and upload extraction with path-traversal protection.
 */
class WorkspaceZipTest {

    @Test
    void zipDirectoryIncludesFilesAndSkipsRuntimeDirs() throws Exception {
        Path ws = Files.createTempDirectory("majo-zip-dl");
        Files.writeString(ws.resolve("notes.md"), "hello", StandardCharsets.UTF_8);
        Path memory = Files.createDirectories(ws.resolve("memory"));
        Files.writeString(memory.resolve("mem.md"), "mem", StandardCharsets.UTF_8);
        Files.createDirectories(ws.resolve("node_modules"));
        Files.writeString(ws.resolve("node_modules").resolve("dep.js"), "skip", StandardCharsets.UTF_8);

        byte[] zip = WorkspaceController.zipDirectory(ws);
        java.util.Set<String> names = new java.util.HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        assertTrue(names.contains("notes.md"));
        assertTrue(names.contains("memory/mem.md"));
        assertFalse(names.contains("node_modules/dep.js"));
    }

    @Test
    void extractAndMergeZipWritesFiles() throws Exception {
        Path ws = Files.createTempDirectory("majo-zip-ul");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            zos.putNextEntry(new ZipEntry("a.txt"));
            zos.write("content-a".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("sub/b.txt"));
            zos.write("content-b".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        WorkspaceController.extractAndMergeZip(buf.toByteArray(), ws);
        assertEquals("content-a", Files.readString(ws.resolve("a.txt")));
        assertEquals("content-b", Files.readString(ws.resolve("sub").resolve("b.txt")));
    }

    @Test
    void extractAndMergeRejectsTraversal() throws Exception {
        Path ws = Files.createTempDirectory("majo-zip-trav");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            zos.putNextEntry(new ZipEntry("../evil.txt"));
            zos.write("evil".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceController.extractAndMergeZip(buf.toByteArray(), ws));
        assertFalse(Files.exists(ws.getParent().resolve("evil.txt")));
    }

    @Test
    void uploadEndpointAcceptsZipMultipart() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            zos.putNextEntry(new ZipEntry("file.txt"));
            zos.write("data".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path ws = Files.createTempDirectory("majo-zip-mock");
        MockMultipartFile mpf = new MockMultipartFile(
                "file", "ws.zip", "application/zip", buf.toByteArray());
        WorkspaceController controller = new WorkspaceController(null, null, null, null);
        var response = controller.uploadWorkspace(mpf, ws);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertTrue(Files.exists(ws.resolve("file.txt")));
    }
}
