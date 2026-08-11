package com.agent.coding.accesscontrol;

import com.agent.coding.skill.SkillService;
import com.agent.coding.skill.SkillStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe persistent store for per-channel access control lists, ported
 * from qwenpaw app/channels/access_control.py (AccessControlStore).
 *
 * <p>Each channel has a whitelist / blacklist (user_id → {remark, username})
 * and a pending list of users awaiting approval. Persisted as JSON at
 * {@code WORKING_DIR/access_control.json}. Mutations enforce mutual exclusion
 * across the three lists (adding to whitelist removes from blacklist and
 * pending, approving moves pending → whitelist carrying remark/username, etc.)
 */
@Component
public final class AccessControlStore {

    private static final Logger log = LoggerFactory.getLogger(AccessControlStore.class);
    private static final String FILE_NAME = "access_control.json";

    private final Path path;
    private final Object lock = new Object();
    private volatile Map<String, Map<String, Object>> data = new LinkedHashMap<>();

    public AccessControlStore() {
        this.path = SkillStore.WORKING_DIR.resolve(FILE_NAME);
        load();
    }

    // ── Persistence ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void load() {
        synchronized (lock) {
            if (!Files.isRegularFile(path)) {
                data = new LinkedHashMap<>();
                return;
            }
            try {
                Map<String, Object> raw = SkillStore.readJson(path, Map.of());
                Map<String, Map<String, Object>> parsed = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : raw.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> m) {
                        Map<String, Object> acl = parseAcl((Map<String, Object>) m);
                        // Drop channels left empty by earlier pre-create behavior.
                        if (!whitelistOf(acl).isEmpty() || !blacklistOf(acl).isEmpty() || !pendingOf(acl).isEmpty()) {
                            parsed.put(e.getKey(), acl);
                        }
                    }
                }
                data = parsed;
            } catch (Exception e) {
                log.warn("Failed to load access control data from {}: {}", path, e.getMessage());
                data = new LinkedHashMap<>();
            }
        }
    }

    private void save() {
        synchronized (lock) {
            try {
                SkillStore.writeJsonAtomic(path, new LinkedHashMap<>(data));
            } catch (Exception e) {
                log.warn("Failed to save access control data to {}: {}", path, e.getMessage());
            }
        }
    }

    // ── Parsing (with backward compatibility) ───────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseAcl(Map<String, Object> raw) {
        Map<String, Object> acl = new LinkedHashMap<>();
        acl.put("whitelist", parseUserMap(raw.get("whitelist")));
        acl.put("blacklist", parseUserMap(raw.get("blacklist")));
        List<Map<String, Object>> pending = new ArrayList<>();
        Object rawPending = raw.get("pending");
        if (rawPending instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    pending.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        acl.put("pending", pending);
        return acl;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseUserMap(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                result.put(String.valueOf(item), userInfo("", ""));
            }
        } else if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object value = e.getValue();
                if (value instanceof Map<?, ?> info) {
                    result.put(String.valueOf(e.getKey()), userInfo(
                            SkillService.str(((Map<String, Object>) info).get("remark"), ""),
                            SkillService.str(((Map<String, Object>) info).get("username"), "")));
                } else {
                    result.put(String.valueOf(e.getKey()), userInfo(value == null ? "" : String.valueOf(value), ""));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> userInfo(String remark, String username) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("remark", remark == null ? "" : remark);
        info.put("username", username == null ? "" : username);
        return info;
    }

    // ── Internal helpers ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> acl(String channel) {
        Map<String, Object> acl = data.computeIfAbsent(channel, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("whitelist", new LinkedHashMap<String, Object>());
            m.put("blacklist", new LinkedHashMap<String, Object>());
            m.put("pending", new ArrayList<Map<String, Object>>());
            return m;
        });
        if (!(acl.get("whitelist") instanceof Map<?, ?>)) {
            acl.put("whitelist", new LinkedHashMap<String, Object>());
        }
        if (!(acl.get("blacklist") instanceof Map<?, ?>)) {
            acl.put("blacklist", new LinkedHashMap<String, Object>());
        }
        if (!(acl.get("pending") instanceof List<?>)) {
            acl.put("pending", new ArrayList<Map<String, Object>>());
        }
        return acl;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> whitelistOf(Map<String, Object> acl) {
        return (Map<String, Object>) acl.get("whitelist");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> blacklistOf(Map<String, Object> acl) {
        return (Map<String, Object>) acl.get("blacklist");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pendingOf(Map<String, Object> acl) {
        return (List<Map<String, Object>>) acl.get("pending");
    }

    private static void removePending(Map<String, Object> acl, String channel, String userId) {
        pendingOf(acl).removeIf(p -> userId.equals(SkillService.str(p.get("user_id")))
                && channel.equals(SkillService.str(p.get("channel"))));
    }

    // ── Query ───────────────────────────────────────────────────────────

    public boolean isWhitelisted(String channel, String userId) {
        synchronized (lock) {
            return whitelistOf(acl(channel)).containsKey(userId);
        }
    }

    public boolean isBlacklisted(String channel, String userId) {
        synchronized (lock) {
            return blacklistOf(acl(channel)).containsKey(userId);
        }
    }

    public Map<String, Object> getAcl(String channel) {
        synchronized (lock) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("whitelist", new LinkedHashMap<>(whitelistOf(acl(channel))));
            copy.put("blacklist", new LinkedHashMap<>(blacklistOf(acl(channel))));
            copy.put("pending", new ArrayList<>(pendingOf(acl(channel))));
            return copy;
        }
    }

    public Map<String, Map<String, Object>> getAllAcls() {
        synchronized (lock) {
            Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : data.entrySet()) {
                Map<String, Object> aclCopy = new LinkedHashMap<>();
                aclCopy.put("whitelist", new LinkedHashMap<>(whitelistOf(e.getValue())));
                aclCopy.put("blacklist", new LinkedHashMap<>(blacklistOf(e.getValue())));
                aclCopy.put("pending", new ArrayList<>(pendingOf(e.getValue())));
                copy.put(e.getKey(), aclCopy);
            }
            return copy;
        }
    }

    /** True when the channel already exists with data in any list (no create). */
    public boolean hasData(String channel) {
        synchronized (lock) {
            Map<String, Object> acl = data.get(channel);
            if (acl == null) {
                return false;
            }
            return !whitelistOf(acl).isEmpty() || !blacklistOf(acl).isEmpty() || !pendingOf(acl).isEmpty();
        }
    }

    // ── Whitelist ───────────────────────────────────────────────────────

    public void addToWhitelist(String channel, String userId, String remark, String username) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            Map<String, Object> wl = whitelistOf(acl);
            Map<String, Object> existing = SkillService.asMap(wl.get(userId));
            wl.put(userId, userInfo(
                    remark == null || remark.isBlank() ? SkillService.str(existing.get("remark"), "") : remark,
                    username == null || username.isBlank() ? SkillService.str(existing.get("username"), "") : username));
            blacklistOf(acl).remove(userId);
            removePending(acl, channel, userId);
            save();
        }
    }

    public void removeFromWhitelist(String channel, String userId) {
        synchronized (lock) {
            whitelistOf(acl(channel)).remove(userId);
            save();
        }
    }

    public boolean updateRemark(String channel, String userId, String remark) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            Map<String, Object> wl = whitelistOf(acl);
            Map<String, Object> bl = blacklistOf(acl);
            if (wl.containsKey(userId)) {
                Map<String, Object> info = SkillService.asMap(wl.get(userId));
                info.put("remark", remark);
                wl.put(userId, info);
                save();
                return true;
            }
            if (bl.containsKey(userId)) {
                Map<String, Object> info = SkillService.asMap(bl.get(userId));
                info.put("remark", remark);
                bl.put(userId, info);
                save();
                return true;
            }
            return false;
        }
    }

    public boolean updateUsername(String channel, String userId, String username) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            Map<String, Object> wl = whitelistOf(acl);
            Map<String, Object> bl = blacklistOf(acl);
            boolean found = false;
            if (wl.containsKey(userId)) {
                Map<String, Object> info = SkillService.asMap(wl.get(userId));
                info.put("username", username);
                wl.put(userId, info);
                found = true;
            }
            if (bl.containsKey(userId)) {
                Map<String, Object> info = SkillService.asMap(bl.get(userId));
                info.put("username", username);
                bl.put(userId, info);
                found = true;
            }
            for (Map<String, Object> p : pendingOf(acl)) {
                if (userId.equals(SkillService.str(p.get("user_id")))
                        && channel.equals(SkillService.str(p.get("channel")))) {
                    p.put("username", username);
                    found = true;
                }
            }
            if (found) {
                save();
            }
            return found;
        }
    }

    // ── Blacklist ───────────────────────────────────────────────────────

    public void addToBlacklist(String channel, String userId, String remark, String username) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            Map<String, Object> bl = blacklistOf(acl);
            Map<String, Object> existing = SkillService.asMap(bl.get(userId));
            bl.put(userId, userInfo(
                    remark == null || remark.isBlank() ? SkillService.str(existing.get("remark"), "") : remark,
                    username == null || username.isBlank() ? SkillService.str(existing.get("username"), "") : username));
            whitelistOf(acl).remove(userId);
            removePending(acl, channel, userId);
            save();
        }
    }

    public void removeFromBlacklist(String channel, String userId) {
        synchronized (lock) {
            blacklistOf(acl(channel)).remove(userId);
            save();
        }
    }

    // ── Pending ─────────────────────────────────────────────────────────

    public void addPending(String channel, String userId, String firstMessage, String username) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            for (Map<String, Object> p : pendingOf(acl)) {
                if (userId.equals(SkillService.str(p.get("user_id")))
                        && channel.equals(SkillService.str(p.get("channel")))) {
                    return;
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user_id", userId);
            entry.put("channel", channel);
            entry.put("timestamp", System.currentTimeMillis() / 1000.0);
            entry.put("first_message", firstMessage == null ? "" : firstMessage.substring(0, Math.min(200, firstMessage.length())));
            entry.put("remark", "");
            entry.put("username", username == null ? "" : username);
            pendingOf(acl).add(entry);
            save();
        }
    }

    public List<Map<String, Object>> getAllPending() {
        synchronized (lock) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> acl : data.values()) {
                result.addAll(pendingOf(acl));
            }
            result.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("timestamp", 0)).doubleValue(),
                    ((Number) a.getOrDefault("timestamp", 0)).doubleValue()));
            return result;
        }
    }

    public boolean updatePendingRemark(String channel, String userId, String remark) {
        synchronized (lock) {
            for (Map<String, Object> p : pendingOf(acl(channel))) {
                if (userId.equals(SkillService.str(p.get("user_id")))
                        && channel.equals(SkillService.str(p.get("channel")))) {
                    p.put("remark", remark);
                    save();
                    return true;
                }
            }
            return false;
        }
    }

    public boolean approvePending(String channel, String userId, String remark) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            String effectiveRemark = remark;
            String username = "";
            for (Map<String, Object> p : pendingOf(acl)) {
                if (userId.equals(SkillService.str(p.get("user_id")))
                        && channel.equals(SkillService.str(p.get("channel")))) {
                    if (effectiveRemark == null || effectiveRemark.isBlank()) {
                        effectiveRemark = SkillService.str(p.get("remark"), "");
                    }
                    username = SkillService.str(p.get("username"), "");
                    break;
                }
            }
            removePending(acl, channel, userId);
            whitelistOf(acl).put(userId, userInfo(effectiveRemark == null ? "" : effectiveRemark, username));
            blacklistOf(acl).remove(userId);
            save();
            return true;
        }
    }

    public boolean denyPending(String channel, String userId, String remark) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            String effectiveRemark = remark;
            String username = "";
            for (Map<String, Object> p : pendingOf(acl)) {
                if (userId.equals(SkillService.str(p.get("user_id")))
                        && channel.equals(SkillService.str(p.get("channel")))) {
                    if (effectiveRemark == null || effectiveRemark.isBlank()) {
                        effectiveRemark = SkillService.str(p.get("remark"), "");
                    }
                    username = SkillService.str(p.get("username"), "");
                    break;
                }
            }
            removePending(acl, channel, userId);
            blacklistOf(acl).put(userId, userInfo(effectiveRemark == null ? "" : effectiveRemark, username));
            whitelistOf(acl).remove(userId);
            save();
            return true;
        }
    }

    public boolean dismissPending(String channel, String userId) {
        synchronized (lock) {
            Map<String, Object> acl = acl(channel);
            int before = pendingOf(acl).size();
            removePending(acl, channel, userId);
            if (pendingOf(acl).size() < before) {
                save();
                return true;
            }
            return false;
        }
    }

    // ── Migration ───────────────────────────────────────────────────────

    /** Import legacy {@code allow_from} entries into the whitelist. */
    public void importAllowFrom(String channel, java.util.Set<String> allowFrom) {
        if (allowFrom == null || allowFrom.isEmpty()) {
            return;
        }
        synchronized (lock) {
            Map<String, Object> wl = whitelistOf(acl(channel));
            for (String uid : allowFrom) {
                if (!wl.containsKey(uid)) {
                    wl.put(uid, userInfo("", ""));
                }
            }
            save();
            log.info("Imported {} allow_from entries to whitelist for channel {}", allowFrom.size(), channel);
        }
    }
}
