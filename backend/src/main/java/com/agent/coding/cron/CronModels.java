package com.agent.coding.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cron job data models,.
 *
 * <p>Jobs are stored/transported as JSON with snake_case fields matching the
 * frontend contract ({@code CronJobSpecInput} in api/types/cronjob.ts) and the

 * represented as a normalized {@link Map} plus typed accessors, because the
 * request payload is permissive ({@code request.input}, {@code meta} etc. are
 * arbitrary JSON) keeps extra fields.
 */
public final class CronModels {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CronModels() {}

    // ------------------------------------------------------------------
    // Cron day-of-week normalization (port of models.py _crontab_dow_to_name)
    // ------------------------------------------------------------------

    private static final Map<String, String> CRONTAB_NUM_TO_NAME = Map.of(
            "0", "sun", "1", "mon", "2", "tue", "3", "wed",
            "4", "thu", "5", "fri", "6", "sat", "7", "sun");

    private static String crontabDowToName(String field) {
        if ("*".equals(field)) return field;
        List<String> out = new ArrayList<>();
        for (String tok : field.split(",")) {
            out.add(convertDowToken(tok));
        }
        return String.join(",", out);
    }

    private static String convertDowToken(String tok) {
        if (tok.contains("/")) {
            int idx = tok.lastIndexOf('/');
            return convertDowToken(tok.substring(0, idx)) + "/" + tok.substring(idx + 1);
        }
        if (tok.contains("-")) {
            int idx = tok.indexOf('-');
            String a = CRONTAB_NUM_TO_NAME.getOrDefault(tok.substring(0, idx), tok.substring(0, idx));
            String b = CRONTAB_NUM_TO_NAME.getOrDefault(tok.substring(idx + 1), tok.substring(idx + 1));
            return a + "-" + b;
        }
        return CRONTAB_NUM_TO_NAME.getOrDefault(tok, tok);
    }

    /**
     * Normalize a cron expression to exactly 5 fields with named day-of-week,
     * matching ScheduleSpec.normalize_cron_5_fields. Returns the normalized
     * string or throws {@link IllegalArgumentException} when unsupported.
     */
    public static String normalizeCron5Fields(String v) {
        if (v == null) throw new IllegalArgumentException("cron must have 5 fields");
        List<String> parts = new ArrayList<>();
        for (String p : v.trim().split("\\s+")) {
            if (!p.isEmpty()) parts.add(p);
        }
        if (parts.size() == 5) {
            parts.set(4, crontabDowToName(parts.get(4)));
            return String.join(" ", parts);
        }
        if (parts.size() == 4) {
            // hour dom month dow
            String hour = parts.get(0), dom = parts.get(1), month = parts.get(2), dow = parts.get(3);
            return "0 " + hour + " " + dom + " " + month + " " + crontabDowToName(dow);
        }
        if (parts.size() == 3) {
            // dom month dow
            String dom = parts.get(0), month = parts.get(1), dow = parts.get(2);
            return "0 0 " + dom + " " + month + " " + crontabDowToName(dow);
        }
        throw new IllegalArgumentException(
                "cron must have 5 fields (or 4/3 fields that can be normalized); seconds not supported");
    }

    // ------------------------------------------------------------------
    // Schedule / dispatch / runtime typed accessors over a spec map
    // ------------------------------------------------------------------

    /** Extract the "schedule" sub-object (never null). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> schedule(Map<String, Object> spec) {
        Object s = spec.get("schedule");
        if (s instanceof Map<?, ?>) return (Map<String, Object>) s;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "cron");
        m.put("cron", "0 9 * * *");
        m.put("timezone", "UTC");
        return m;
    }

    /** Extract the "dispatch" sub-object (never null). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> dispatch(Map<String, Object> spec) {
        Object d = spec.get("dispatch");
        if (d instanceof Map<?, ?>) return (Map<String, Object>) d;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "channel");
        m.put("channel", "console");
        m.put("mode", "stream");
        m.put("silent", false);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("user_id", "");
        target.put("session_id", "");
        m.put("target", target);
        m.put("meta", new LinkedHashMap<>());
        return m;
    }

    /** Extract the "runtime" sub-object (never null). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> runtime(Map<String, Object> spec) {
        Object r = spec.get("runtime");
        if (r instanceof Map<?, ?>) return (Map<String, Object>) r;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_concurrency", 1);
        m.put("timeout_seconds", 120);
        m.put("misfire_grace_seconds", 600);
        m.put("share_session", true);
        m.put("tool_safety", false);
        return m;
    }

    /** Extract the "request" sub-object, or null when absent. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> request(Map<String, Object> spec) {
        Object r = spec.get("request");
        if (r instanceof Map<?, ?>) return (Map<String, Object>) r;
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> target(Map<String, Object> dispatch) {
        Object t = dispatch.get("target");
        if (t instanceof Map<?, ?>) return (Map<String, Object>) t;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", "");
        m.put("session_id", "");
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean def) {
        return o == null ? def : Boolean.TRUE.equals(o);
    }

    // ------------------------------------------------------------------
    // Spec validation (port of ScheduleSpec + CronJobSpec model_validators)
    // ------------------------------------------------------------------

    /**
     * Validate and normalize a raw spec map in place, mirroring the original
     * pydantic validators. Throws IllegalArgumentException with a user-facing
     * message on validation errors.
     */
    @SuppressWarnings("unchecked")
    public static void validateSpec(Map<String, Object> spec) {
        Map<String, Object> schedule = schedule(spec);
        String type = str(schedule.get("type"));
        if (type == null) type = "cron";

        if ("cron".equals(type)) {
            String cron = str(schedule.get("cron"));
            if (cron == null || cron.isBlank()) {
                throw new IllegalArgumentException("schedule.type is cron but cron is empty");
            }
            schedule.put("cron", normalizeCron5Fields(cron));
            schedule.put("run_at", null);
            schedule.put("repeat_every_days", null);
            schedule.put("repeat_end_type", null);
            schedule.put("repeat_until", null);
            schedule.put("repeat_count", null);
        } else if ("once".equals(type)) {
            if (schedule.get("run_at") == null) {
                throw new IllegalArgumentException("schedule.type is once but run_at is missing");
            }
            schedule.put("cron", null);
            Integer repeatEveryDays = toInt(schedule.get("repeat_every_days"));
            if (repeatEveryDays == null) {
                schedule.put("repeat_end_type", null);
                schedule.put("repeat_until", null);
                schedule.put("repeat_count", null);
            } else {
                String endType = str(schedule.get("repeat_end_type"));
                if (endType == null) endType = "never";
                if ("never".equals(endType)) {
                    schedule.put("repeat_until", null);
                    schedule.put("repeat_count", null);
                } else if ("until".equals(endType)) {
                    if (schedule.get("repeat_until") == null) {
                        throw new IllegalArgumentException(
                                "repeat_end_type is until but repeat_until is missing");
                    }
                    if (parseInstant(str(schedule.get("repeat_until")))
                            .compareTo(parseInstant(str(schedule.get("run_at")))) <= 0) {
                        throw new IllegalArgumentException(
                                "repeat_until must be later than run_at (deadline must be after execution time)");
                    }
                    schedule.put("repeat_count", null);
                } else if ("count".equals(endType)) {
                    if (schedule.get("repeat_count") == null) {
                        throw new IllegalArgumentException(
                                "repeat_end_type is count but repeat_count is missing");
                    }
                    schedule.put("repeat_until", null);
                } else {
                    throw new IllegalArgumentException("invalid repeat_end_type: " + endType);
                }
            }
        } else {
            throw new IllegalArgumentException("schedule.type must be 'cron' or 'once'");
        }
        spec.put("schedule", schedule);

        // dispatch defaults
        Map<String, Object> dispatch = dispatch(spec);
        if (dispatch.get("type") == null) dispatch.put("type", "channel");
        if (dispatch.get("channel") == null) dispatch.put("channel", "console");
        if (dispatch.get("mode") == null) dispatch.put("mode", "stream");
        if (dispatch.get("silent") == null) dispatch.put("silent", false);
        if (dispatch.get("meta") == null) dispatch.put("meta", new LinkedHashMap<>());
        Map<String, Object> target = target(dispatch);
        if (target.get("user_id") == null) target.put("user_id", "");
        if (target.get("session_id") == null) target.put("session_id", "");
        dispatch.put("target", target);
        spec.put("dispatch", dispatch);

        // runtime defaults
        Map<String, Object> runtime = runtime(spec);
        if (runtime.get("max_concurrency") == null) runtime.put("max_concurrency", 1);
        if (runtime.get("timeout_seconds") == null) runtime.put("timeout_seconds", 120);
        if (runtime.get("misfire_grace_seconds") == null) runtime.put("misfire_grace_seconds", 600);
        if (runtime.get("share_session") == null) runtime.put("share_session", true);
        if (runtime.get("tool_safety") == null) runtime.put("tool_safety", false);
        spec.put("runtime", runtime);

        // task_type validation
        String taskType = str(spec.get("task_type"));
        if (taskType == null) taskType = "agent";
        boolean silent = bool(dispatch.get("silent"), false);
        if ("text".equals(taskType)) {
            String text = str(spec.get("text"));
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("task_type is text but text is empty");
            }
            if (silent) {
                throw new IllegalArgumentException("silent delivery is only supported for agent tasks");
            }
            spec.put("request", null);
        } else if ("agent".equals(taskType)) {
            Map<String, Object> request = request(spec);
            if (request == null) {
                throw new IllegalArgumentException("task_type is agent but request is missing");
            }
            // Keep request.user_id and request.session_id in sync with target
            request.put("user_id", target.get("user_id"));
            request.put("session_id", target.get("session_id"));
            spec.put("request", request);
        } else {
            throw new IllegalArgumentException("task_type must be 'text' or 'agent'");
        }
        spec.put("task_type", taskType);

        // save_result_to_inbox default: text+cron => OFF, else ON
        if (spec.get("save_result_to_inbox") == null) {
            boolean textCron = "text".equals(taskType) && "cron".equals(type);
            spec.put("save_result_to_inbox", !textCron);
        }
        if (spec.get("enabled") == null) spec.put("enabled", true);
        if (spec.get("meta") == null) spec.put("meta", new LinkedHashMap<>());
        if (spec.get("name") == null) {
            throw new IllegalArgumentException("name is required");
        }
    }

    /** Create a fresh spec map with defaults (no id), then validate. */
    public static Map<String, Object> newSpecWithDefaults() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "");
        spec.put("enabled", true);
        spec.put("save_result_to_inbox", true);
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("type", "cron");
        schedule.put("cron", "0 9 * * *");
        schedule.put("timezone", "UTC");
        spec.put("schedule", schedule);
        spec.put("task_type", "agent");
        spec.put("text", "");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", null);
        spec.put("request", request);
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("type", "channel");
        dispatch.put("channel", "console");
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("user_id", "");
        target.put("session_id", "");
        dispatch.put("target", target);
        dispatch.put("mode", "stream");
        dispatch.put("silent", false);
        dispatch.put("meta", new LinkedHashMap<>());
        spec.put("dispatch", dispatch);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("max_concurrency", 1);
        runtime.put("timeout_seconds", 120);
        runtime.put("misfire_grace_seconds", 600);
        runtime.put("share_session", true);
        runtime.put("tool_safety", false);
        spec.put("runtime", runtime);
        spec.put("meta", new LinkedHashMap<>());
        return spec;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse an ISO-8601 instant (or offset datetime) to epoch millis. */
    public static Long parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(s).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    /** Format epoch millis as ISO-8601 with offset (e.g. 2026-08-08T06:29:44Z). */
    public static String formatInstant(Long epochMillis, String timezone) {
        if (epochMillis == null) return null;
        ZoneId zone;
        try {
            zone = timezone == null || timezone.isBlank() ? ZoneId.of("UTC") : ZoneId.of(timezone);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone));
    }

    /** Deep-copy a JSON-compatible map (used to avoid mutating caller payloads). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> src) {
        try {
            JsonNode node = MAPPER.valueToTree(src);
            return MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>(src);
        }
    }
}
