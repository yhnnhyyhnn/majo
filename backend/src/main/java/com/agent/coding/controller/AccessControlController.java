package com.agent.coding.controller;

import com.agent.coding.accesscontrol.AccessControlStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel access control endpoints, ported from qwenpaw app/routers/access_control.py.
 *
 * <p>Manages per-channel whitelist / blacklist / pending approval entries,
 * persisted by {@link AccessControlStore}. The frontend /channels page uses
 * these to manage who may talk to the bot on each channel. The actual message
 * interception (gate) lives in the channel runtime, which Majo does not port
 * yet; the list management itself is fully functional.
 */
@RestController
@RequestMapping("/api/access-control")
@CrossOrigin(origins = "*")
public class AccessControlController {

    private static final List<String> BUILTIN_CHANNEL_KEYS = List.of(
            "console", "dingtalk", "wecom", "feishu", "slack", "telegram",
            "discord", "matrix", "mattermost", "mqtt", "wechat", "qq",
            "voice", "xiaoyi", "yuanbao", "onebot");

    private final AccessControlStore store;

    public AccessControlController(AccessControlStore store) {
        this.store = store;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @GetMapping
    public Map<String, Object> listAcls() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String channel : BUILTIN_CHANNEL_KEYS) {
            if (store.hasData(channel)) {
                result.put(channel, store.getAcl(channel));
            }
        }
        // Include non-builtin channels that have data.
        for (Map.Entry<String, Map<String, Object>> e : store.getAllAcls().entrySet()) {
            String channel = e.getKey();
            if (!result.containsKey(channel) && store.hasData(channel)) {
                result.put(channel, e.getValue());
            }
        }
        return result;
    }

    @GetMapping("/pending/all")
    public List<Map<String, Object>> pendingAll() {
        return store.getAllPending();
    }

    @PostMapping("/pending/approve")
    public Map<String, Object> approvePending(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> entries = entriesOf(body);
        for (Map<String, Object> entry : entries) {
            store.approvePending(str(entry, "channel"), str(entry, "user_id"), str(entry, "remark"));
        }
        return Map.of("status", "ok", "count", entries.size());
    }

    @PostMapping("/pending/deny")
    public Map<String, Object> denyPending(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> entries = entriesOf(body);
        for (Map<String, Object> entry : entries) {
            store.denyPending(str(entry, "channel"), str(entry, "user_id"), str(entry, "remark"));
        }
        return Map.of("status", "ok", "count", entries.size());
    }

    @PostMapping("/pending/dismiss")
    public Map<String, Object> dismissPending(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> entries = entriesOf(body);
        for (Map<String, Object> entry : entries) {
            store.dismissPending(str(entry, "channel"), str(entry, "user_id"));
        }
        return Map.of("status", "ok", "count", entries.size());
    }

    @PostMapping("/pending/remark")
    public ResponseEntity<?> updatePendingRemark(@RequestBody Map<String, Object> body) {
        boolean found = store.updatePendingRemark(str(body, "channel"), str(body, "user_id"), str(body, "remark"));
        if (!found) {
            return ResponseEntity.status(404).body(Map.of("detail", "Pending entry not found"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/whitelist/add")
    public Map<String, Object> whitelistAdd(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> entries = entriesOf(body);
        for (Map<String, Object> entry : entries) {
            store.addToWhitelist(str(entry, "channel"), str(entry, "user_id"),
                    str(entry, "remark"), str(entry, "username"));
        }
        return Map.of("status", "ok", "count", entries.size());
    }

    @PostMapping("/whitelist/remove")
    public Map<String, Object> whitelistRemove(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> entries = entriesOf(body);
        for (Map<String, Object> entry : entries) {
            store.removeFromWhitelist(str(entry, "channel"), str(entry, "user_id"));
        }
        return Map.of("status", "ok", "count", entries.size());
    }

    @PostMapping("/blacklist/add")
    public Map<String, Object> blacklistAdd(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> entries = entriesOf(body);
        for (Map<String, Object> entry : entries) {
            store.addToBlacklist(str(entry, "channel"), str(entry, "user_id"),
                    str(entry, "remark"), str(entry, "username"));
        }
        return Map.of("status", "ok", "count", entries.size());
    }

    @PostMapping("/blacklist/remove")
    public Map<String, Object> blacklistRemove(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> entries = entriesOf(body);
        for (Map<String, Object> entry : entries) {
            store.removeFromBlacklist(str(entry, "channel"), str(entry, "user_id"));
        }
        return Map.of("status", "ok", "count", entries.size());
    }

    @PostMapping("/remark")
    public ResponseEntity<?> updateRemark(@RequestBody Map<String, Object> body) {
        boolean found = store.updateRemark(str(body, "channel"), str(body, "user_id"), str(body, "remark"));
        if (!found) {
            return ResponseEntity.status(404).body(Map.of("detail", "User not found in any list"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/username")
    public ResponseEntity<?> updateUsername(@RequestBody Map<String, Object> body) {
        boolean found = store.updateUsername(str(body, "channel"), str(body, "user_id"), str(body, "username"));
        if (!found) {
            return ResponseEntity.status(404).body(Map.of("detail", "User not found in any list"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/{channel}")
    public Map<String, Object> channelAcl(@PathVariable String channel) {
        return store.getAcl(channel);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(Map<String, Object> body) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object entries = body.get("entries");
        if (entries instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add(new LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        return result;
    }
}
