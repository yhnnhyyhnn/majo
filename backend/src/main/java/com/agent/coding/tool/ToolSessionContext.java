package com.agent.coding.tool;

/**
 * Per-tool-thread session context, populated by {@code ToolGuardHook} in
 * PRE_ACTING so tools that need to surface interactive elements (ACP
 * permission requests) know which majo chat session they are running in.
 * Empty/absent = non-interactive context (heartbeat, cron) — such tools
 * should degrade instead of blocking on user input.
 */
public final class ToolSessionContext {

    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();

    private ToolSessionContext() {
    }

    public static void set(String sessionId, String agentId) {
        SESSION.set(sessionId);
        AGENT.set(agentId);
    }

    public static String sessionId() {
        String s = SESSION.get();
        return s == null ? "" : s;
    }

    public static String agentId() {
        String a = AGENT.get();
        return a == null ? "default" : a;
    }

    public static void clear() {
        SESSION.remove();
        AGENT.remove();
    }
}
