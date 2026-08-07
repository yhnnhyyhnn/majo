package com.agent.coding.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);
    private static final Path PLUGINS_DIR = com.agent.coding.skill.SkillStore.WORKING_DIR.resolve("plugins");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Map<String, Object>> registeredChannels = new LinkedHashMap<>();

    static { try { Files.createDirectories(PLUGINS_DIR); } catch (IOException ignored) {} }

    @PostConstruct
    void scan() {
        rescan();
    }

    public void rescan() {
        registeredChannels.clear();
        File[] dirs = PLUGINS_DIR.toFile().listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            File mf = new File(dir, "plugin.json");
            if (!mf.exists()) continue;
            try {
                Map<String, Object> manifest = mapper.readValue(mf, new TypeReference<Map<String, Object>>() {});
                String pluginId = Objects.toString(manifest.getOrDefault("id", dir.getName()), "");
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) manifest.getOrDefault("meta", Map.of());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> channels = (List<Map<String, Object>>) meta.getOrDefault("channels", List.of());
                for (Map<String, Object> ch : channels) {
                    String name = Objects.toString(ch.get("name"), "");
                    if (name.isBlank()) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("label", ch.getOrDefault("label", name));
                    entry.put("description", ch.getOrDefault("description", ""));
                    entry.put("plugin_id", pluginId);
                    entry.put("config_fields", ch.getOrDefault("config_fields", List.of()));
                    entry.put("icon", ch.getOrDefault("icon", ""));
                    entry.put("doc_url", ch.getOrDefault("doc_url", ""));
                    registeredChannels.put(name, entry);
                }
            } catch (Exception e) {
                log.warn("Failed to scan plugin channel from {}: {}", dir.getName(), e.getMessage());
            }
        }
        log.info("PluginRegistry: {} channel(s) registered from plugins", registeredChannels.size());
    }

    public Map<String, Map<String, Object>> getRegisteredChannels() {
        return Collections.unmodifiableMap(registeredChannels);
    }
}
