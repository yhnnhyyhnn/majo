package com.agent.coding.controller;

import com.agent.coding.skill.SkillStore;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local model (llama.cpp) management
 * app/routers/local_models.py. Majo detects the llama-server binary and scans
 * the local models directory; downloading and running the llama.cpp server is
 * a future integration (the endpoints return explicit "not installed" states).
 */
@RestController
@RequestMapping("/api/local-models")
@CrossOrigin(origins = "*")
public class LocalModelsController {

    private static final List<String> LLAMA_BINARIES = List.of("llama-server", "llama-server.exe");
    private static final String DEFAULT_MODELS_DIR = "local-models";

    private Path modelsDir() {
        return SkillStore.WORKING_DIR.resolve(DEFAULT_MODELS_DIR);
    }

    private String findLlamaServer() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            for (String binary : LLAMA_BINARIES) {
                Path candidate = Path.of(dir, binary);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private boolean isServerRunning() {
        // Best-effort: check if anything is listening on the default llama.cpp port.
        return false;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", findLlamaServer() != null);
        result.put("models_dir", modelsDir().toString());
        return result;
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        return config();
    }

    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        List<Map<String, Object>> result = new ArrayList<>();
        Path dir = modelsDir();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        File[] entries = dir.toFile().listFiles();
        if (entries == null) {
            return result;
        }
        for (java.io.File f : entries) {
            if (f.isDirectory() || !isModelFile(f.getName())) {
                continue;
            }
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("id", f.getName());
            model.put("name", f.getName());
            model.put("size_bytes", f.length());
            model.put("downloaded", true);
            model.put("source", "auto");
            result.add(model);
        }
        return result;
    }

    private static boolean isModelFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".gguf") || lower.endsWith(".bin") || lower.endsWith(".safetensors");
    }

    @GetMapping("/models/{model_id}")
    public Map<String, Object> modelDetail(@PathVariable String model_id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", model_id);
        result.put("name", model_id);
        result.put("size_bytes", 0);
        result.put("downloaded", false);
        Path file = modelsDir().resolve(model_id);
        if (Files.isRegularFile(file)) {
            result.put("size_bytes", file.toFile().length());
            result.put("downloaded", true);
        }
        return result;
    }

    @DeleteMapping("/models/{model_id}")
    public Map<String, Object> deleteModel(@PathVariable String model_id) {
        Path file = modelsDir().resolve(model_id).normalize();
        if (!file.startsWith(modelsDir().normalize())) {
            return Map.of("status", "error", "message", "Invalid model id");
        }
        try {
            if (Files.deleteIfExists(file)) {
                return Map.of("status", "ok", "message", "Model deleted");
            }
        } catch (Exception ignored) {
        }
        return Map.of("status", "error", "message", "Model not found");
    }

    @GetMapping("/models/download")
    public Map<String, Object> modelDownloadProgress() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "idle");
        result.put("model_name", null);
        result.put("downloaded_bytes", 0);
        result.put("total_bytes", null);
        result.put("speed_bytes_per_sec", 0.0);
        result.put("source", null);
        result.put("error", null);
        result.put("local_path", null);
        return result;
    }

    @PostMapping("/models/download")
    public Map<String, Object> startModelDownload(@RequestBody Map<String, Object> body) {
        return Map.of("status", "not_supported", "message",
                "Model download requires llama.cpp integration");
    }

    @DeleteMapping("/models/download")
    public Map<String, Object> cancelModelDownload() {
        return Map.of("status", "ok", "message", "No active download");
    }

    @GetMapping("/server")
    public Map<String, Object> serverStatus() {
        String binary = findLlamaServer();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", binary != null && isServerRunning());
        result.put("installable", binary != null);
        result.put("installed", binary != null);
        result.put("port", null);
        result.put("model_name", null);
        result.put("message", binary == null
                ? "llama-server not found in PATH; install llama.cpp to use local models"
                : "llama.cpp server is not running, please start the server first");
        return result;
    }

    @PostMapping("/server")
    public Map<String, Object> startServer(@RequestBody Map<String, Object> body) {
        return Map.of("status", "not_supported",
                "message", "llama.cpp server startup requires integration");
    }

    @DeleteMapping("/server")
    public Map<String, Object> stopServer() {
        return Map.of("status", "ok", "message", "No server running");
    }

    @GetMapping("/server/download")
    public Map<String, Object> serverDownloadProgress() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "idle");
        result.put("model_name", null);
        result.put("downloaded_bytes", 0);
        result.put("total_bytes", null);
        result.put("speed_bytes_per_sec", 0.0);
        result.put("source", null);
        result.put("error", null);
        result.put("local_path", null);
        return result;
    }

    @PostMapping("/server/download")
    public Map<String, Object> startServerDownload() {
        return Map.of("status", "not_supported", "message",
                "llama.cpp download requires integration");
    }

    @DeleteMapping("/server/download")
    public Map<String, Object> cancelServerDownload() {
        return Map.of("status", "ok", "message", "No active download");
    }

    @GetMapping("/server/update")
    public Map<String, Object> serverUpdateStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("has_update", false);
        result.put("current_version", null);
        result.put("latest_version", null);
        return result;
    }
}
