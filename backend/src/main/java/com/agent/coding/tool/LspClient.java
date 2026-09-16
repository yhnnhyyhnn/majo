package com.agent.coding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal LSP JSON-RPC client over a language-server subprocess' stdio
 * (ADR-0010), ported from QwenPaw's {@code _lsp_client.py}. Deliberately
 * no diagnostics / incremental sync / streaming — request-response only,
 * every operation preceded by a full-text {@code didOpen}.
 *
 * <p>Servers are pooled per (workspace, language); {@link #get} reuses a
 * healthy process or spawns a new one. All failures surface as
 * {@link LspException} so the tool layer can return model-readable text.
 */
public final class LspClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(LspClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long REQUEST_TIMEOUT_SECONDS = 15;
    private static final long INIT_TIMEOUT_SECONDS = 30;

    /** One supported language: discovery argv + extensions. */
    public record ServerSpec(String language, List<String> extensions, List<List<String>> candidates) {
        String displayName() {
            return language;
        }
    }

    /** Well-known server specs; discovery is PATH-based (QwenPaw parity). */
    public static final List<ServerSpec> SERVER_SPECS = List.of(
            new ServerSpec("typescript",
                    List.of(".ts", ".tsx", ".mts", ".cts"),
                    List.of(List.of("typescript-language-server", "--stdio"))),
            new ServerSpec("javascript",
                    List.of(".js", ".jsx", ".mjs", ".cjs"),
                    List.of(List.of("typescript-language-server", "--stdio"))),
            new ServerSpec("python",
                    List.of(".py", ".pyi"),
                    List.of(List.of("pyright-langserver", "--stdio"),
                            List.of("pylsp"))));

    private final Path workspace;
    private final String language;
    private Process process;
    private OutputStream stdin;
    private InputStream stdout;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong openedUris = new AtomicLong();
    private Thread readerThread;
    private volatile String initError;

    private LspClient(Path workspace, String language) {
        this.workspace = workspace;
        this.language = language;
    }

    // ── Pool ─────────────────────────────────────────────────────────

    private static final Map<String, LspClient> POOL = new ConcurrentHashMap<>();

    /** Get or spawn the pooled client for (workspace, language). */
    public static synchronized LspClient get(Path workspace, String language) throws LspException {
        String key = workspace + "\u0000" + language;
        LspClient existing = POOL.get(key);
        if (existing != null && existing.isAlive()) {
            return existing;
        }
        if (existing != null) {
            existing.close();
            POOL.remove(key);
        }
        LspClient fresh = new LspClient(workspace, language);
        fresh.start();
        POOL.put(key, fresh);
        return fresh;
    }

    /** Shut down every pooled server (workspace switch / shutdown). */
    public static synchronized void shutdownAll() {
        for (LspClient c : POOL.values()) {
            c.close();
        }
        POOL.clear();
    }

    /** Discover argv for a language by probing PATH; null = unavailable. */
    public static List<String> discover(String language) {
        for (ServerSpec spec : SERVER_SPECS) {
            if (spec.language().equals(language)) {
                for (List<String> candidate : spec.candidates()) {
                    if (onPath(candidate.get(0))) {
                        return candidate;
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** Languages with a discoverable server (for tool description text). */
    public static List<String> availableLanguages() {
        List<String> out = new ArrayList<>();
        for (ServerSpec spec : SERVER_SPECS) {
            if (discover(spec.language()) != null) {
                out.add(spec.language());
            }
        }
        return out;
    }

    /** Map a file name to a supported language id, or null. */
    public static String languageFor(String fileName) {
        String lower = fileName.toLowerCase();
        for (ServerSpec spec : SERVER_SPECS) {
            for (String ext : spec.extensions()) {
                if (lower.endsWith(ext)) {
                    return spec.language();
                }
            }
        }
        return null;
    }

    private static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        String[] names = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new String[]{executable + ".exe", executable + ".cmd", executable + ".bat", executable}
                : new String[]{executable};
        for (String dir : path.split("[;]")) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                if (Files.isExecutable(Path.of(dir.strip(), name))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    private void start() throws LspException {
        List<String> argv = discover(language);
        if (argv == null) {
            throw new LspException("没有找到 " + language + " 的语言服务器"
                    + "（需要 typescript-language-server / pyright / pylsp 之一）。");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(workspace.toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            stdin = process.getOutputStream();
            stdout = process.getInputStream();
        } catch (IOException e) {
            throw new LspException("启动语言服务器失败: " + e.getMessage());
        }
        readerThread = new Thread(this::readLoop, "lsp-reader-" + language);
        readerThread.setDaemon(true);
        readerThread.start();

        // initialize → initialized (LSP handshake).
        ObjectNode params = MAPPER.createObjectNode();
        params.put("processId", (int) ProcessHandle.current().pid());
        params.put("rootUri", workspace.toUri().toString());
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("textDocument").putObject("synchronization");
        try {
            request("initialize", params, INIT_TIMEOUT_SECONDS);
            notify("initialized", MAPPER.createObjectNode());
        } catch (LspException e) {
            close();
            throw new LspException("LSP initialize 失败: " + e.getMessage());
        }
        log.info("[lsp] {} server up for workspace {}", language, workspace);
    }

    public boolean isAlive() {
        return process != null && process.isAlive() && initError == null;
    }

    @Override
    public synchronized void close() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.cancel(false);
        }
        pending.clear();
    }

    // ── Requests ─────────────────────────────────────────────────────

    /** Execute one LSP operation; text is read into the server first. */
    public synchronized JsonNode operate(String operation, String filePath, Integer line,
                                         Integer character, String query) throws LspException {
        if (!isAlive()) {
            throw new LspException("语言服务器未运行。");
        }
        try {
            switch (operation) {
                case "goToDefinition", "findReferences", "hover", "goToImplementation" -> {
                    requirePosition(filePath, line, character);
                    String uri = didOpen(filePath);
                    return request(operation, positionParams(uri, line, character, operation),
                            REQUEST_TIMEOUT_SECONDS);
                }
                case "documentSymbol" -> {
                    if (filePath == null || filePath.isBlank()) {
                        throw new LspException("documentSymbol 需要 file_path。");
                    }
                    String uri = didOpen(filePath);
                    ObjectNode params = MAPPER.createObjectNode();
                    params.putObject("textDocument").put("uri", uri);
                    return request("textDocument/documentSymbol", params, REQUEST_TIMEOUT_SECONDS);
                }
                case "workspaceSymbol" -> {
                    if (query == null || query.isBlank()) {
                        throw new LspException("workspaceSymbol 需要 query。");
                    }
                    ObjectNode params = MAPPER.createObjectNode();
                    params.put("query", query);
                    return request("workspace/symbol", params, REQUEST_TIMEOUT_SECONDS);
                }
                default -> throw new LspException("未知操作 '" + operation
                        + "'（可用: goToDefinition, findReferences, hover, goToImplementation, "
                        + "documentSymbol, workspaceSymbol）");
            }
        } catch (IOException e) {
            throw new LspException("读取文件失败: " + e.getMessage());
        }
    }

    private static void requirePosition(String filePath, Integer line, Integer character)
            throws LspException {
        if (filePath == null || filePath.isBlank() || line == null || character == null) {
            throw new LspException("该操作需要 file_path、line、character（均为 1-based）。");
        }
    }

    private ObjectNode positionParams(String uri, Integer line, Integer character, String operation) {
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode td = params.putObject("textDocument");
        td.put("uri", uri);
        ObjectNode pos = params.putObject("position");
        pos.put("line", line - 1);
        pos.put("character", character - 1);
        if ("findReferences".equals(operation)) {
            params.putObject("context").put("includeDeclaration", true);
        }
        return params;
    }

    /** didOpen the file (full text) once per call; no incremental sync by design. */
    private String didOpen(String relativePath) throws IOException, LspException {
        Path file = workspace.resolve(relativePath.strip()).normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在: " + relativePath);
        }
        String uri = file.toUri().toString();
        // Re-open on every call: cheap, and guarantees fresh content even
        // after external edits (no incremental sync by design).
        String text = Files.readString(file, StandardCharsets.UTF_8);
        ObjectNode params = MAPPER.createObjectNode();
        ObjectNode td = params.putObject("textDocument");
        td.put("uri", uri);
        td.put("languageId", language);
        td.put("version", openedUris.incrementAndGet());
        td.put("text", text);
        notify("textDocument/didOpen", params);
        return uri;
    }

    // ── JSON-RPC framing ─────────────────────────────────────────────

    private void notify(String method, ObjectNode params) throws LspException {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.set("params", params);
        write(msg);
    }

    private JsonNode request(String method, ObjectNode params, long timeoutSeconds) throws LspException {
        int id = nextId.getAndIncrement();
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.set("params", params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            write(msg);
            JsonNode result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (result.has("error")) {
                throw new LspException("LSP " + method + " 失败: "
                        + result.path("error").path("message").asText("unknown"));
            }
            return result;
        } catch (LspException e) {
            throw e;
        } catch (Exception e) {
            throw new LspException("LSP " + method + " 超时或失败: " + e.getMessage());
        } finally {
            pending.remove(id);
        }
    }

    private synchronized void write(ObjectNode msg) throws LspException {
        try {
            byte[] body = MAPPER.writeValueAsBytes(msg);
            String header = "Content-Length: " + body.length + "\r\n\r\n";
            stdin.write(header.getBytes(StandardCharsets.US_ASCII));
            stdin.write(body);
            stdin.flush();
        } catch (IOException e) {
            throw new LspException("写入语言服务器失败: " + e.getMessage());
        }
    }

    /** Reader loop: parse frames, complete pending futures, ignore notifications. */
    private void readLoop() {
        try {
            while (process != null && process.isAlive()) {
                String header = readFrameHeader(stdout);
                if (header == null) {
                    break;
                }
                int length = parseContentLength(header);
                if (length < 0) {
                    break;
                }
                byte[] body = stdout.readNBytes(length);
                if (body.length < length) {
                    break;
                }
                JsonNode msg = MAPPER.readTree(body);
                if (msg.has("id") && msg.get("id").isInt()
                        && (msg.has("result") || msg.has("error"))) {
                    CompletableFuture<JsonNode> f = pending.remove(msg.get("id").asInt());
                    if (f != null) {
                        f.complete(msg);
                    }
                }
                // Notifications (publishDiagnostics etc.) are ignored by design.
            }
        } catch (Exception e) {
            log.debug("[lsp:{}] reader stopped: {}", language, e.getMessage());
        }
        initError = "server exited";
        for (CompletableFuture<JsonNode> f : pending.values()) {
            f.cancel(false);
        }
        pending.clear();
    }

    /**
     * Read one LSP header block (terminated by {@code \r\n\r\n}).
     * Package-visible for tests; null on EOF or runaway header.
     */
    static String readFrameHeader(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n' && sb.length() >= 2
                    && sb.charAt(sb.length() - 2) == '\n') {
                return sb.toString();
            }
            sb.append((char) c);
            prev = c;
            if (sb.length() > 16 * 1024) {
                return null; // runaway header
            }
        }
        return null;
    }

    static int parseContentLength(String header) {
        for (String line : header.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "Content-Length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** Domain failure carrying a model-readable message. */
    public static final class LspException extends Exception {
        public LspException(String message) {
            super(message);
        }
    }
}
