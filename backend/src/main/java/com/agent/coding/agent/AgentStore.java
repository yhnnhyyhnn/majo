package com.agent.coding.agent;

import com.agent.coding.skill.SkillNotFoundException;
import com.agent.coding.skill.SkillService;
import com.agent.coding.skill.SkillStore;
import com.agent.coding.skill.SkillsError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Multi-agent registry backed by {@code WORKING_DIR/agents.json}.
 *
 * The file stores agent profile references (id, name, description,
 * workspace_dir, enabled, pinned, backend, language) plus the persisted
 * UI order.  The global skill pool ({@code WORKING_DIR/skill_pool}) stays
 * shared across all agents; each agent owns an independent workspace
 * ({@code workspace_dir/skills} + {@code workspace_dir/skill.json}).
 *
 * Port of qwenpaw/config/config.py (AgentsConfig / AgentProfileRef) kept in
 * a single JSON file per the user's "配置文件 agents.json" decision.
 */
public class AgentStore {

    private static final Logger log = LoggerFactory.getLogger(AgentStore.class);

    public static final String SCHEMA_VERSION = "agents.v1";
    public static final String DEFAULT_AGENT_ID = "default";
    public static final String DEFAULT_AGENT_NAME = "majo";
    public static final String DEFAULT_AGENT_DESC = "Majo AI Coding Agent";

    public static final Path AGENTS_FILE = SkillStore.WORKING_DIR.resolve("agents.json");

    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final int AGENT_ID_MIN_LENGTH = 2;
    private static final int AGENT_ID_MAX_LENGTH = 64;

    // ------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------

    /**
     * Ensure agents.json exists with the default agent.  Idempotent, safe
     * to call on every request (mirrors SkillPoolService lazy bootstrap).
     */
    public static synchronized void ensureAgentsInitialized() {
        if (Files.isRegularFile(AGENTS_FILE)) {
            return;
        }
        try {
            Files.createDirectories(AGENTS_FILE.getParent());
            Map<String, Object> config = defaultConfig();
            SkillStore.writeJsonAtomic(AGENTS_FILE, config);
            log.info("Initialized agents.json with default agent");
        } catch (Exception e) {
            log.warn("Failed to initialize agents.json", e);
            throw new SkillsError("Failed to initialize agents.json: " + e.getMessage());
        }
    }

    private static Map<String, Object> defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", SCHEMA_VERSION);
        config.put("active_agent", DEFAULT_AGENT_ID);
        List<String> order = new ArrayList<>();
        order.add(DEFAULT_AGENT_ID);
        config.put("agent_order", order);
        Map<String, Object> profiles = new LinkedHashMap<>();
        profiles.put(DEFAULT_AGENT_ID, defaultProfile());
        config.put("profiles", profiles);
        return config;
    }

    private static Map<String, Object> defaultProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", DEFAULT_AGENT_ID);
        profile.put("name", DEFAULT_AGENT_NAME);
        profile.put("description", DEFAULT_AGENT_DESC);
        profile.put("workspace_dir", SkillStore.WORKING_DIR.toString());
        profile.put("enabled", true);
        profile.put("pinned", true);
        profile.put("backend", "majo");
        profile.put("language", "zh");
        return profile;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Load the raw agents.json config map (never null). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadConfig() {
        ensureAgentsInitialized();
        Map<String, Object> defaults = defaultConfig();
        Map<String, Object> config = SkillStore.readJson(AGENTS_FILE, defaults);
        // Self-heal: missing default profile (e.g. hand-edited file).
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        if (!profiles.containsKey(DEFAULT_AGENT_ID)) {
            profiles.put(DEFAULT_AGENT_ID, defaultProfile());
            config.put("profiles", profiles);
            List<String> order = toStringList(config.get("agent_order"));
            if (!order.contains(DEFAULT_AGENT_ID)) {
                order.add(0, DEFAULT_AGENT_ID);
                config.put("agent_order", order);
            }
            SkillStore.writeJsonAtomic(AGENTS_FILE, config);
        }
        return config;
    }

    /** Ordered profile ids (default first, then pinned, then regular). */
    @SuppressWarnings("unchecked")
    public static List<String> agentOrder() {
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        List<String> order = toStringList(config.get("agent_order"));
        List<String> result = new ArrayList<>();
        for (String id : order) {
            if (profiles.containsKey(id) && !result.contains(id)) {
                result.add(id);
            }
        }
        for (String id : profiles.keySet()) {
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        // Group: default, pinned, regular.
        List<String> pinned = new ArrayList<>();
        List<String> regular = new ArrayList<>();
        for (String id : result) {
            if (id.equals(DEFAULT_AGENT_ID)) continue;
            Map<String, Object> profile = SkillService.asMap(profiles.get(id));
            if (Boolean.TRUE.equals(profile.get("pinned"))) {
                pinned.add(id);
            } else {
                regular.add(id);
            }
        }
        List<String> grouped = new ArrayList<>();
        if (result.contains(DEFAULT_AGENT_ID)) grouped.add(DEFAULT_AGENT_ID);
        grouped.addAll(pinned);
        grouped.addAll(regular);
        return grouped;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listProfiles() {
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : agentOrder()) {
            Map<String, Object> profile = SkillService.asMap(profiles.get(id));
            if (!profile.isEmpty()) {
                result.add(profile);
            }
        }
        return result;
    }

    /** Return the profile map for an agent, or null if unknown. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getProfile(String agentId) {
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        Map<String, Object> profile = SkillService.asMap(profiles.get(agentId));
        return profile.isEmpty() ? null : profile;
    }

    /** True if the agent id exists in the registry. */
    public static boolean hasAgent(String agentId) {
        return getProfile(agentId) != null;
    }

    /** Resolve an agent's workspace directory; unknown agents throw 404-style error. */
    public static Path workspaceDirForAgent(String agentId) {
        if (agentId == null || agentId.isBlank() || agentId.equals(DEFAULT_AGENT_ID)) {
            return SkillStore.WORKING_DIR;
        }
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            throw new SkillNotFoundException("Workspace '" + agentId + "' not found");
        }
        String ws = SkillService.str(profile.get("workspace_dir"));
        if (ws == null || ws.isBlank()) {
            throw new SkillNotFoundException("Workspace '" + agentId + "' not found");
        }
        return Path.of(ws).toAbsolutePath().normalize();
    }

    /** Workspace summaries used by the skill system ({@code /api/skills/workspaces}). */
    public static List<Map<String, String>> listWorkspaces() {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, Object> profile : listProfiles()) {
            Map<String, String> ws = new LinkedHashMap<>();
            String id = SkillService.str(profile.get("id"));
            ws.put("agent_id", id);
            ws.put("agent_name", SkillService.str(profile.get("name"), id));
            ws.put("workspace_dir", SkillService.str(profile.get("workspace_dir"), ""));
            result.add(ws);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    /**
     * Create an agent profile.  Validates the id, creates the workspace
     * directory (default {@code WORKING_DIR/workspaces/{id}}), and persists
     * the profile atomically.  Returns the created profile.
     */
    public static Map<String, Object> createAgent(Map<String, Object> spec) {
        ensureAgentsInitialized();
        String id = SkillService.str(spec.get("id"));
        validateNewAgentId(id);

        Path workspaceDir = resolveWorkspaceDir(id, SkillService.str(spec.get("workspace_dir")));
        try {
            Files.createDirectories(workspaceDir);
            Files.createDirectories(SkillStore.getWorkspaceSkillsDir(workspaceDir));
        } catch (Exception e) {
            throw new SkillsError("Cannot create workspace dir: " + workspaceDir);
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", id);
        profile.put("name", SkillService.str(spec.get("name"), id));
        profile.put("description", SkillService.str(spec.get("description"), ""));
        profile.put("workspace_dir", workspaceDir.toString());
        profile.put("enabled", true);
        profile.put("pinned", false);
        profile.put("backend", SkillService.str(spec.get("backend"), "majo"));
        profile.put("language", SkillService.str(spec.get("language"), "zh"));
        if (spec.get("active_model") != null) {
            profile.put("active_model", spec.get("active_model"));
        }

        Map<String, Object> config = loadConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        profiles.put(id, profile);
        List<String> order = toStringList(config.get("agent_order"));
        if (!order.contains(id)) {
            order.add(id);
        }
        config.put("profiles", profiles);
        config.put("agent_order", order);
        SkillStore.writeJsonAtomic(AGENTS_FILE, config);
        log.info("Created agent: {} (workspace={})", id, workspaceDir);
        return profile;
    }

    /** Update mutable fields of an existing agent profile. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> updateAgent(String agentId, Map<String, Object> updates) {
        ensureAgentsInitialized();
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        Map<String, Object> profile = SkillService.asMap(profiles.get(agentId));
        if (profile.isEmpty()) {
            throw new SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        for (String key : new String[]{"name", "description", "backend", "language"}) {
            if (updates.containsKey(key)) {
                profile.put(key, SkillService.str(updates.get(key)));
            }
        }
        if (updates.containsKey("enabled")) {
            profile.put("enabled", Boolean.TRUE.equals(updates.get("enabled")));
        }
        if (updates.containsKey("pinned")) {
            profile.put("pinned", Boolean.TRUE.equals(updates.get("pinned")));
        }
        if (updates.containsKey("active_model")) {
            Object model = updates.get("active_model");
            if (model == null) {
                profile.remove("active_model");
            } else {
                profile.put("active_model", model);
            }
        }
        if (updates.containsKey("running")) {
            Object running = updates.get("running");
            if (running == null) {
                profile.remove("running");
            } else {
                profile.put("running", running);
            }
        }
        if (updates.containsKey("approval_level")) {
            Object level = updates.get("approval_level");
            if (level == null) {
                profile.remove("approval_level");
            } else {
                profile.put("approval_level", SkillService.str(level));
            }
        }
        if (updates.containsKey("user_timezone")) {
            Object tz = updates.get("user_timezone");
            if (tz == null) {
                profile.remove("user_timezone");
            } else {
                profile.put("user_timezone", SkillService.str(tz));
            }
        }
        if (updates.containsKey("acp")) {
            Object acp = updates.get("acp");
            if (acp == null) {
                profile.remove("acp");
            } else {
                profile.put("acp", acp);
            }
        }
        profiles.put(agentId, profile);
        config.put("profiles", profiles);
        SkillStore.writeJsonAtomic(AGENTS_FILE, config);
        return profile;
    }

    /** Return the persisted {@code running} config map for an agent (never null, may be empty). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getRunningConfig(String agentId) {
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            return new LinkedHashMap<>();
        }
        Object running = profile.get("running");
        if (running instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    /** Return the persisted approval level for an agent, or "AUTO" when unset. */
    public static String getApprovalLevel(String agentId) {
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            return "AUTO";
        }
        String level = SkillService.str(profile.get("approval_level"), "AUTO");
        return level.isBlank() ? "AUTO" : level;
    }

    /**
     * Persist the {@code running} config (and optionally the profile-level
     * {@code approval_level}) for an agent. Mirrors qwenpaw's running-config
     * PUT: approval_level lives on the profile, the rest under {@code running}.
     */
    public static void saveRunningConfig(String agentId,
                                         Map<String, Object> running,
                                         String approvalLevel) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("running", running);
        if (approvalLevel != null) {
            updates.put("approval_level", approvalLevel);
        }
        updateAgent(agentId, updates);
    }

    /** Return the per-agent timezone, or {@code fallback} when the agent has none configured. */
    public static String getUserTimezone(String agentId, String fallback) {
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            return fallback;
        }
        String tz = SkillService.str(profile.get("user_timezone"), "");
        return tz.isBlank() ? fallback : tz;
    }

    /** Persist the per-agent timezone. */
    public static void setUserTimezone(String agentId, String timezone) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("user_timezone", timezone);
        updateAgent(agentId, updates);
    }

    /** Return the per-agent ACP config map (never null; may be empty). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getACPConfig(String agentId) {
        Map<String, Object> profile = getProfile(agentId);
        if (profile == null) {
            return new LinkedHashMap<>();
        }
        Object acp = profile.get("acp");
        if (acp instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    /** Persist the per-agent ACP config. */
    public static void saveACPConfig(String agentId, Map<String, Object> acpConfig) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("acp", acpConfig);
        updateAgent(agentId, updates);
    }

    /** Return the global ACP node_path stored in the agents.json root (never null). */
    @SuppressWarnings("unchecked")
    public static String getGlobalACPNodePath() {
        Map<String, Object> config = loadConfig();
        Object acp = config.get("acp");
        if (acp instanceof Map<?, ?> map) {
            return SkillService.str(((Map<String, Object>) map).get("node_path"), "");
        }
        return "";
    }

    /** Persist the global ACP node_path at the agents.json root. */
    public static void setGlobalACPNodePath(String nodePath) {
        ensureAgentsInitialized();
        Map<String, Object> config = loadConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> acp = SkillService.asMap(config.get("acp"));
        acp.put("node_path", nodePath == null ? "" : nodePath);
        config.put("acp", acp);
        SkillStore.writeJsonAtomic(AGENTS_FILE, config);
    }

    /** Delete an agent profile (default agent cannot be deleted). */
    @SuppressWarnings("unchecked")
    public static void deleteAgent(String agentId) {
        ensureAgentsInitialized();
        if (agentId == null || agentId.equals(DEFAULT_AGENT_ID)) {
            throw new SkillsError("Cannot delete the default agent");
        }
        Map<String, Object> config = loadConfig();
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        if (!profiles.containsKey(agentId)) {
            throw new SkillNotFoundException("Agent '" + agentId + "' not found");
        }
        profiles.remove(agentId);
        List<String> order = toStringList(config.get("agent_order"));
        order.remove(agentId);
        config.put("profiles", profiles);
        config.put("agent_order", order);
        SkillStore.writeJsonAtomic(AGENTS_FILE, config);
        log.info("Deleted agent: {}", agentId);
    }

    /** Persist the full ordered agent id list. */
    public static void setAgentOrder(List<String> agentIds) {
        ensureAgentsInitialized();
        if (agentIds == null) {
            throw new SkillsError("agent_ids must not be null");
        }
        Map<String, Object> config = loadConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = SkillService.asMap(config.get("profiles"));
        if (new HashSet<>(agentIds).size() != agentIds.size()) {
            throw new SkillsError("Each configured agent ID must appear exactly once.");
        }
        for (String id : agentIds) {
            if (!profiles.containsKey(id)) {
                throw new SkillsError("Each configured agent ID must appear exactly once.");
            }
        }
        config.put("agent_order", new ArrayList<>(agentIds));
        SkillStore.writeJsonAtomic(AGENTS_FILE, config);
    }

    /** Toggle an agent's enabled state (default agent cannot be disabled). */
    public static void setAgentEnabled(String agentId, boolean enabled) {
        if (agentId != null && agentId.equals(DEFAULT_AGENT_ID) && !enabled) {
            throw new SkillsError("Cannot disable the default agent");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("enabled", enabled);
        updateAgent(agentId, updates);
    }

    /** Pin / unpin an agent (default agent cannot be unpinned). */
    public static void setAgentPinned(String agentId, boolean pinned) {
        if (agentId != null && agentId.equals(DEFAULT_AGENT_ID) && !pinned) {
            throw new SkillsError("Cannot unpin the default agent");
        }
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("pinned", pinned);
        updateAgent(agentId, updates);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Validate a brand-new agent id (charset, length, reserved, uniqueness). */
    private static void validateNewAgentId(String id) {
        if (id == null || id.isBlank()) {
            throw new SkillsError("Agent ID must not be empty");
        }
        if (id.length() < AGENT_ID_MIN_LENGTH || id.length() > AGENT_ID_MAX_LENGTH) {
            throw new SkillsError("Agent ID must be between " + AGENT_ID_MIN_LENGTH
                    + " and " + AGENT_ID_MAX_LENGTH + " characters, got " + id.length());
        }
        if (!AGENT_ID_PATTERN.matcher(id).matches()) {
            throw new SkillsError("Agent ID '" + id + "' contains invalid characters. "
                    + "Use letters, digits, '-' or '_'.");
        }
        if (id.equals(DEFAULT_AGENT_ID)) {
            throw new SkillsError("Agent ID '" + id + "' is reserved and cannot be used.");
        }
        if (hasAgent(id)) {
            throw new SkillsError("Agent ID '" + id + "' already exists.");
        }
    }

    /** Default workspace is {@code WORKING_DIR/workspaces/{id}} unless overridden. */
    private static Path resolveWorkspaceDir(String agentId, String raw) {
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw.trim()).toAbsolutePath().normalize();
        }
        return SkillStore.WORKING_DIR.resolve("workspaces").resolve(agentId).normalize();
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }
}
