package com.agent.coding.channel;

import com.agent.coding.skill.SkillService;
import com.agent.coding.skill.SkillStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the most recent channel contact (QwenPaw last_dispatch semantics):
 * who talked to the agent last, on which channel, and where a proactive
 * reply should go. Single global entry — the heartbeat {@code target=last}
 * routes through here.
 */
@Component
public class LastContactStore {

    private static final Logger log = LoggerFactory.getLogger(LastContactStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_NAME = "last_contact.json";

    private final Path path;
    private final Object lock = new Object();
    private volatile Map<String, Object> latest;

    public LastContactStore() {
        this.path = SkillStore.WORKING_DIR.resolve(FILE_NAME);
        load();
    }

    /** Record a successful contact. */
    public void record(String channel, String to, String userId) {
        if (channel == null || channel.isBlank() || to == null || to.isBlank()) {
            return;
        }
        synchronized (lock) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("channel", channel);
            entry.put("to", to);
            entry.put("user_id", userId == null ? "" : userId);
            entry.put("updated_at", System.currentTimeMillis() / 1000.0);
            latest = entry;
            save(entry);
        }
    }

    /** Most recent contact, or null. Returns {channel, to, user_id, updated_at}. */
    public Map<String, Object> latest() {
        return latest;
    }

    private void load() {
        try {
            if (Files.isRegularFile(path)) {
                latest = MAPPER.readValue(Files.readString(path, java.nio.charset.StandardCharsets.UTF_8), Map.class);
            }
        } catch (Exception e) {
            log.warn("Failed to load last contact from {}: {}", path, e.getMessage());
            latest = null;
        }
    }

    private void save(Map<String, Object> entry) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, MAPPER.writeValueAsString(entry),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to save last contact to {}: {}", path, e.getMessage());
        }
    }

    /** Read one field as string (helper for consumers). */
    public static String str(Map<String, Object> m, String key) {
        return m == null ? null : SkillService.str(m.get(key), null);
    }
}
