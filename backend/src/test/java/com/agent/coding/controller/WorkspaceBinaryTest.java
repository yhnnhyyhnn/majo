package com.agent.coding.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests workspace binary-file preview: MIME detection, raw byte streaming,
 * and the 415/404/path-traversal guards.
 */
class WorkspaceBinaryTest {

    private final WorkspaceController controller = new WorkspaceController(null, null, null, null, null);

    @Test
    void servesPngWithImageMime() throws IOException {
        Path ws = Files.createTempDirectory("majo-bin-png");
        Files.write(ws.resolve("img.png"), new byte[]{1, 2, 3});
        ResponseEntity<Resource> resp = controller.binaryFile(ws, "img.png");
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertEquals("image/png", resp.getHeaders().getContentType().toString());
        assertArrayEquals(new byte[]{1, 2, 3},
                resp.getBody().getInputStream().readAllBytes());
    }

    @Test
    void servesNestedPdfPath() throws IOException {
        Path ws = Files.createTempDirectory("majo-bin-pdf");
        Path docs = Files.createDirectories(ws.resolve("docs"));
        Files.write(docs.resolve("report.pdf"), new byte[]{9, 9, 9});
        ResponseEntity<Resource> resp = controller.binaryFile(ws, "docs/report.pdf");
        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertEquals("application/pdf", resp.getHeaders().getContentType().toString());
    }

    @Test
    void rejectsUnsupportedExtension() throws IOException {
        Path ws = Files.createTempDirectory("majo-bin-unsup");
        Files.write(ws.resolve("file.exe"), new byte[]{1});
        ResponseEntity<Resource> resp = controller.binaryFile(ws, "file.exe");
        assertEquals(415, resp.getStatusCode().value());
    }

    @Test
    void missingFileReturns404() {
        Path ws = Path.of(System.getProperty("java.io.tmpdir"), "majo-bin-missing");
        ResponseEntity<Resource> resp = controller.binaryFile(ws, "nope.png");
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void pathTraversalRejected() {
        Path ws = Path.of(System.getProperty("java.io.tmpdir"), "majo-bin-trav");
        ResponseEntity<Resource> resp = controller.binaryFile(ws, "../secret.png");
        assertTrue(resp.getStatusCode().is4xxClientError());
    }
}
