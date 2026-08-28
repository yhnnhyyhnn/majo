package com.agent.coding.tool;

import com.agent.coding.agent.AgentStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Current date/time tool. The timezone is resolved in order:
 * explicit tool param → persisted user timezone (agents.json
 * {@code user_timezone}, set by {@code set_user_timezone}) → system default.
 */
@Component
public class GetCurrentTimeTool {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z (EEEE)");

    @Tool(name = "get_current_time", description = "获取当前日期和时间，可指定 IANA 时区")
    public String getCurrentTime(
        @ToolParam(name = "timezone", description = "IANA 时区，如 Asia/Shanghai (可选)") String timezone
    ) {
        return ZonedDateTime.now(resolveZone(timezone)).format(FMT);
    }

    static ZoneId resolveZone(String timezone) {
        if (timezone != null && !timezone.isBlank()) {
            try {
                return ZoneId.of(timezone);
            } catch (Exception ignored) {
            }
        }
        Object saved = AgentStore.loadConfig().get("user_timezone");
        if (saved != null && !String.valueOf(saved).isBlank()) {
            try {
                return ZoneId.of(String.valueOf(saved));
            } catch (Exception ignored) {
            }
        }
        return ZoneId.systemDefault();
    }
}
