package com.agent.coding.channel;

import com.agent.coding.agent.AgentStore;
import com.agent.coding.skill.SkillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel configuration API — mirrors the QwenPaw console contract:
 * /api/config/channels{/types,/schemas,/{name},/{name}/qrcode...}.
 * Config is persisted in agents.json under the {@code channels} key and
 * hot-reloaded into {@link ChannelRegistry}.
 */
@RestController
@RequestMapping("/api/config/channels")
@CrossOrigin(origins = "*")
public class ChannelsConfigController {

    private final ChannelRegistry registry;
    private final ChannelSchemas schemas;
    private final com.agent.coding.service.PluginRegistry pluginRegistry;

    public ChannelsConfigController(ChannelRegistry registry, ChannelSchemas schemas,
                                    com.agent.coding.service.PluginRegistry pluginRegistry) {
        this.registry = registry;
        this.schemas = schemas;
        this.pluginRegistry = pluginRegistry;
    }

    // ── storage helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channelsSection() {
        Map<String, Object> config = AgentStore.loadConfig();
        Object ch = config.get("channels");
        return ch instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }

    // ── endpoints ────────────────────────────────────────────────────

    @GetMapping("/types")
    public List<String> types() {
        List<String> all = new ArrayList<>(registry.channelIds());
        for (String p : pluginRegistry.getRegisteredChannels().keySet()) {
            if (!all.contains(p)) {
                all.add(p);
            }
        }
        return all;
    }

    /** Full config map (every known channel, with defaults). */
    @GetMapping
    public Map<String, Object> listChannels() {
        Map<String, Object> stored = channelsSection();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String id : registry.channelIds()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = stored.get(id) instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            cfg.putIfAbsent("enabled", "console".equals(id));
            cfg.putIfAbsent("bot_prefix", "");
            result.put(id, cfg);
        }
        return result;
    }

    @GetMapping("/schemas")
    public Map<String, Object> schemas() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String id : registry.channelIds()) {
            result.put(id, schemas.forChannel(id));
        }
        result.putAll(pluginRegistry.getRegisteredChannels());
        return result;
    }

    /** Update all channels at once and hot-reload. */
    @PutMapping
    public Map<String, Object> updateChannels(@RequestBody Map<String, Object> body) {
        Map<String, Object> clean = new LinkedHashMap<>();
        for (String id : registry.channelIds()) {
            Object v = body.get(id);
            if (v instanceof Map<?, ?> m) {
                clean.put(id, new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        AgentStore.updateRoot("channels", clean);
        registry.reload(clean);
        return listChannels();
    }

    @GetMapping("/{channel_name}")
    public ResponseEntity<?> getChannel(@PathVariable String channel_name) {
        if (!registry.channelIds().contains(channel_name)) {
            return notFound(channel_name);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = channelsSection().get(channel_name) instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        cfg.putIfAbsent("enabled", false);
        cfg.putIfAbsent("bot_prefix", "");
        return ResponseEntity.ok(cfg);
    }

    @PutMapping("/{channel_name}")
    public ResponseEntity<?> updateChannel(@PathVariable String channel_name,
                                           @RequestBody Map<String, Object> body) {
        if (!registry.channelIds().contains(channel_name)) {
            return notFound(channel_name);
        }
        Map<String, Object> all = channelsSection();
        Map<String, Object> cfg = new LinkedHashMap<>(body);
        all.put(channel_name, cfg);
        AgentStore.updateRoot("channels", all);
        registry.reload(all);
        cfg.putIfAbsent("enabled", false);
        cfg.putIfAbsent("bot_prefix", "");
        return ResponseEntity.ok(cfg);
    }

    /** 渠道健康状态（真实运行状态）。 */
    @GetMapping("/{channel_name}/health")
    public Map<String, Object> channelHealth(@PathVariable String channel_name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel_name);
        m.put("status", registry.isRunning(channel_name) ? "healthy" : "stopped");
        m.put("detail", "");
        return m;
    }

    /** 重启渠道。 */
    @PostMapping("/{channel_name}/restart")
    public Map<String, Object> channelRestart(@PathVariable String channel_name) {
        Map<String, Object> cfg = channelConfigMap(channel_name);
        registry.restart(channel_name, cfg);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel_name);
        m.put("status", "restarted");
        m.put("detail", "");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channelConfigMap(String name) {
        Map<String, Object> all = channelsSection();
        Object v = all.get(name);
        return v instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
    }

    /** QR login (WeChat/QQ) is not implementable over plain HTTP — 501. */
    @GetMapping("/{channel_name}/qrcode")
    public ResponseEntity<?> qrcode(@PathVariable String channel_name) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("detail", "channel '" + channel_name
                        + "' does not support QR login in majo"));
    }

    @GetMapping("/{channel_name}/qrcode/status")
    public ResponseEntity<?> qrcodeStatus(@PathVariable String channel_name) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("detail", "channel '" + channel_name
                        + "' does not support QR login in majo"));
    }

    private static ResponseEntity<?> notFound(String name) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Unknown channel: " + name));
    }
}
