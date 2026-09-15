package com.agent.coding.memory;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner-aware registry of {@link MemoryBackend} implementations (ADR-0008).
 * Java counterpart of QwenPaw's MemoryBackendRegistry: backends register as
 * Spring beans, {@link #resolve} selects per agent by the configured
 * {@code memory_manager_backend} id and falls back through the remaining
 * backends when the selection is unavailable.
 */
@Service
public class MemoryBackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackendRegistry.class);

    private final Map<String, MemoryBackend> backends = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, MemoryBackend> active = new ConcurrentHashMap<>();

    public MemoryBackendRegistry(List<MemoryBackend> beans) {
        for (MemoryBackend b : beans) {
            MemoryBackend prev = backends.putIfAbsent(b.id(), b);
            if (prev != null) {
                log.warn("[memory] duplicate backend id '{}' — keeping first registration", b.id());
            }
        }
        log.info("[memory] registered backends: {}", backends.keySet());
    }

    /** Registered backend ids in registration (fallback) order. */
    public List<String> backendIds() {
        return List.copyOf(backends.keySet());
    }

    /**
     * Resolve the active backend for an agent: configured id first, then
     * any other registered backend that reports available. The resolved
     * instance is cached per agent until {@link #evict} is called.
     */
    public MemoryBackend resolve(String agentId) {
        MemoryBackend cached = active.get(agentId);
        if (cached != null && cached.isAvailable()) {
            return cached;
        }
        String configured = configuredBackendId(agentId);
        MemoryBackend chosen = null;
        if (configured != null && backends.containsKey(configured)) {
            MemoryBackend preferred = backends.get(configured);
            if (preferred.isAvailable()) {
                chosen = preferred;
            } else {
                log.warn("[memory] backend '{}' unavailable for agent '{}'; falling back",
                        configured, agentId);
            }
        }
        if (chosen == null) {
            for (MemoryBackend b : backends.values()) {
                if (b.isAvailable()) {
                    chosen = b;
                    break;
                }
            }
        }
        if (chosen == null) {
            return null;
        }
        startIfNeeded(agentId, chosen);
        active.put(agentId, chosen);
        return chosen;
    }

    /** Drop the cached backend for an agent (config change / shutdown). */
    public void evict(String agentId) {
        MemoryBackend prev = active.remove(agentId);
        if (prev != null) {
            try {
                prev.close();
            } catch (Exception e) {
                log.debug("[memory] close({}) failed: {}", prev.id(), e.getMessage());
            }
        }
    }

    private void startIfNeeded(String agentId, MemoryBackend backend) {
        try {
            backend.start(new MemoryBackendContext(
                    agentId,
                    AgentStore.workspaceDirForAgent(agentId),
                    runningMemoryConfig(agentId),
                    agentLanguage(agentId)));
        } catch (Exception e) {
            log.warn("[memory] start({}) for agent '{}' failed: {}",
                    backend.id(), agentId, e.getMessage());
        }
    }

    /** Configured backend id, or null when unset/unknown (falls back). */
    static String configuredBackendId(String agentId) {
        try {
            Map<String, Object> running = AgentStore.getRunningConfig(agentId);
            Object value = running.get("memory_manager_backend");
            String id = value == null ? null : String.valueOf(value).trim().toLowerCase();
            return (id == null || id.isBlank() || "remelight".equals(id)) ? "keyword" : id;
        } catch (Exception e) {
            return "keyword";
        }
    }

    private static Map<String, Object> runningMemoryConfig(String agentId) {
        try {
            Map<String, Object> running = AgentStore.getRunningConfig(agentId);
            Map<String, Object> reme = SkillService.asMap(running.get("reme_light_memory_config"));
            return new LinkedHashMap<>(reme);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String agentLanguage(String agentId) {
        try {
            var profile = AgentStore.getProfile(agentId);
            return profile != null && profile.get("language") != null
                    ? String.valueOf(profile.get("language")) : "zh";
        } catch (Exception e) {
            return "zh";
        }
    }
}
