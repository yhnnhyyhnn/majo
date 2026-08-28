package com.agent.coding.tool;

import com.agent.coding.agent.AgentStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Persist the user's IANA timezone under {@code agents.json user_timezone}.
 * Read back by {@link GetCurrentTimeTool}.
 */
@Component
public class SetUserTimezoneTool {

    @Tool(name = "set_user_timezone", description = "设置用户时区（IANA 名称，如 Asia/Shanghai）")
    public String setUserTimezone(
        @ToolParam(name = "timezone", description = "IANA 时区名称") String timezone
    ) {
        if (timezone == null || timezone.isBlank()) {
            return "错误: timezone 不能为空";
        }
        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            return "错误: 无效的时区 '" + timezone + "'，请使用 IANA 名称（如 Asia/Shanghai）";
        }
        AgentStore.updateRoot("user_timezone", timezone);
        return "已设置用户时区: " + timezone;
    }
}
