package com.agent.coding.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@code AgentsRunningConfig} structure, ported from
 * The GET
 * /workspace/running-config endpoint deep-merges these defaults with the
 * persisted profile so the frontend always receives a complete structure.
 */
public final class RunningConfigDefaults {

    private RunningConfigDefaults() {
    }

    public static Map<String, Object> defaultConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("max_iters", 100);

        Map<String, Object> loop = new LinkedHashMap<>();
        Map<String, Object> iteration = new LinkedHashMap<>();
        iteration.put("enabled", true);
        iteration.put("max_iterations", null);
        loop.put("iteration", iteration);

        Map<String, Object> doomLoop = new LinkedHashMap<>();
        doomLoop.put("enabled", true);
        doomLoop.put("window_size", 3);
        doomLoop.put("similarity_threshold", 1.0);
        List<Map<String, Object>> stages = new ArrayList<>();
        Map<String, Object> stage1 = new LinkedHashMap<>();
        stage1.put("after", 3);
        stage1.put("action", "modify_prompt");
        stage1.put("prompt", "[WARNING] Repetitive pattern detected. You are repeating "
                + "similar actions without progress. Try a completely different approach.");
        Map<String, Object> stage2 = new LinkedHashMap<>();
        stage2.put("after", 4);
        stage2.put("action", "stop");
        stage2.put("prompt", "Doom loop: agent stuck after 4 consecutive repetitions");
        stages.add(stage1);
        stages.add(stage2);
        doomLoop.put("stages", stages);
        doomLoop.put("in_loop_modes", false);
        loop.put("doom_loop", doomLoop);

        Map<String, Object> rubric = new LinkedHashMap<>();
        rubric.put("enabled", false);
        rubric.put("prompt", "You did not call any tool in the last turn. If the task is "
                + "truly complete, confirm it. Otherwise, continue working with tool calls.");
        rubric.put("max_interventions", 1);
        rubric.put("in_loop_modes", false);
        loop.put("rubric", rubric);

        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("max_iterations", 20);
        goal.put("max_tokens", 300_000);
        loop.put("goal", goal);

        Map<String, Object> mission = new LinkedHashMap<>();
        mission.put("max_iterations", 20);
        mission.put("max_retries_per_story", 3);
        mission.put("default_verification_instructions", "");
        mission.put("default_verify_command", "");
        loop.put("mission", mission);

        loop.put("custom_modes", new ArrayList<>());
        config.put("loop", loop);

        config.put("llm_retry_enabled", true);
        config.put("llm_max_retries", 3);
        config.put("llm_backoff_base", 1.0);
        config.put("llm_backoff_cap", 10.0);
        config.put("llm_max_concurrent", 5);
        config.put("llm_max_qpm", 60);
        config.put("llm_rate_limit_pause", 1.0);
        config.put("llm_rate_limit_jitter", 0.0);
        config.put("llm_acquire_timeout", 30.0);

        config.put("shell_command_timeout", 60.0);
        config.put("shell_command_executable", "");
        config.put("max_input_length", 131072);
        config.put("history_max_length", 10000);

        config.put("context_manager_backend", "light");

        Map<String, Object> lightContext = new LinkedHashMap<>();
        lightContext.put("strategy", "scroll");
        lightContext.put("dialog_path", "dialog");
        lightContext.put("token_count_estimate_divisor", 4);

        Map<String, Object> contextCompact = new LinkedHashMap<>();
        contextCompact.put("enabled", true);
        contextCompact.put("compact_threshold_ratio", 0.8);
        contextCompact.put("reserve_threshold_ratio", 0.1);
        lightContext.put("context_compact_config", contextCompact);

        Map<String, Object> toolPruning = new LinkedHashMap<>();
        toolPruning.put("enabled", true);
        toolPruning.put("pruning_recent_n", 2);
        toolPruning.put("pruning_old_msg_max_bytes", 3000);
        toolPruning.put("pruning_recent_msg_max_bytes", 50000);
        toolPruning.put("offload_retention_days", 30);
        toolPruning.put("tool_results_cache", "tool_results");
        toolPruning.put("exempt_file_extensions", List.of(".md"));
        toolPruning.put("exempt_tool_names", List.of("chat_with_agent"));
        lightContext.put("tool_result_pruning_config", toolPruning);

        Map<String, Object> scroll = new LinkedHashMap<>();
        scroll.put("db_filename", "history.db");
        scroll.put("repl_timeout_s", 300);
        scroll.put("history_retention_days", 30);
        scroll.put("allow_unsandboxed", false);
        scroll.put("offload_dialog", false);
        lightContext.put("scroll_config", scroll);

        config.put("light_context_config", lightContext);

        Map<String, Object> autoTitle = new LinkedHashMap<>();
        autoTitle.put("enabled", true);
        autoTitle.put("timeout_seconds", 30.0);
        config.put("auto_title_config", autoTitle);

        config.put("memory_manager_backend", "remelight");
        config.put("adbpg_memory_config", null);

        Map<String, Object> remeLight = new LinkedHashMap<>();
        remeLight.put("metadata_dir", "mem_metadata");
        remeLight.put("session_dir", "mem_session");
        remeLight.put("mem_session_dir", "mem_agent");
        remeLight.put("resource_dir", "resource");
        remeLight.put("daily_dir", "memory");
        remeLight.put("digest_dir", "digest");
        remeLight.put("summarize_when_compact", true);
        remeLight.put("inbox_push_enabled", true);
        remeLight.put("auto_memory_interval", 5);
        remeLight.put("dream_cron_enabled", true);
        remeLight.put("dream_cron", "0 23 * * *");

        Map<String, Object> autoSearch = new LinkedHashMap<>();
        autoSearch.put("enabled", false);
        autoSearch.put("max_results", 2);
        remeLight.put("auto_memory_search_config", autoSearch);

        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("backend", "openai");
        embedding.put("api_key", "");
        embedding.put("base_url", "");
        embedding.put("model_name", "");
        embedding.put("dimensions", 1024);
        embedding.put("enable_cache", true);
        embedding.put("use_dimensions", false);
        embedding.put("max_cache_size", 10000);
        embedding.put("max_input_length", 8192);
        embedding.put("max_batch_size", 10);
        remeLight.put("embedding_model_config", embedding);

        config.put("reme_light_memory_config", remeLight);
        config.put("daily_memory_dir", "memory");
        return config;
    }

    /** Deep-merge {@code override} over {@code base} (nested maps merged recursively). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object overrideVal = entry.getValue();
            Object baseVal = base.get(entry.getKey());
            if (overrideVal instanceof Map<?, ?> overrideMap
                    && baseVal instanceof Map<?, ?> baseMap) {
                result.put(entry.getKey(),
                        deepMerge((Map<String, Object>) baseMap, (Map<String, Object>) overrideMap));
            } else {
                result.put(entry.getKey(), overrideVal);
            }
        }
        return result;
    }
}
