package com.agent.coding.commands;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command registry for priority-based control-command detection, ported from
 * 
 *
 * <p>Registers slash commands with priority levels; {@code isControlCommand}
 * determines whether a message is a control command (exact prefix match,
 * followed by whitespace or end-of-string). Used by the frontend approval
 * flow and chat routing.
 */
@Component
public class CommandRegistry {

    private final Map<String, Integer> commandToLevel = new LinkedHashMap<>();
    private final int defaultLevel = 20;

    public CommandRegistry() {
        register("/stop", 0);
        register("/daemon status", 10);
        register("/daemon restart", 10);
        register("/daemon reload-config", 10);
        register("/daemon version", 10);
        register("/daemon logs", 10);
        register("/daemon approve", 10);
        register("/status", 10);
        register("/restart", 10);
        register("/reload-config", 10);
        register("/reload_config", 10);
        register("/version", 10);
        register("/logs", 10);
        register("/approve", 10);
        register("/deny", 10);
        register("/approval", 10);
    }

    public void register(String prefix, int level) {
        commandToLevel.put(prefix.toLowerCase(), level);
    }

    /** True when the query matches a registered command prefix. */
    public boolean isControlCommand(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lowered = query.strip().toLowerCase();
        if (!lowered.startsWith("/")) {
            return false;
        }
        List<String> prefixes = commandToLevel.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        for (String prefix : prefixes) {
            if (lowered.startsWith(prefix)) {
                int nextCharIdx = prefix.length();
                if (nextCharIdx >= lowered.length()) {
                    return true;
                }
                char next = lowered.charAt(nextCharIdx);
                if (next == ' ' || next == '\t' || next == '\n') {
                    return true;
                }
            }
        }
        return false;
    }

    /** Priority level (lower = higher priority), defaulting to 20 (normal). */
    public int priorityLevel(String query) {
        if (query == null || query.isBlank()) {
            return defaultLevel;
        }
        String lowered = query.strip().toLowerCase();
        if (!lowered.startsWith("/")) {
            return defaultLevel;
        }
        List<Map.Entry<String, Integer>> entries = commandToLevel.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList();
        for (Map.Entry<String, Integer> entry : entries) {
            String prefix = entry.getKey();
            if (lowered.startsWith(prefix)) {
                int nextCharIdx = prefix.length();
                if (nextCharIdx >= lowered.length()) {
                    return entry.getValue();
                }
                char next = lowered.charAt(nextCharIdx);
                if (next == ' ' || next == '\t' || next == '\n') {
                    return entry.getValue();
                }
            }
        }
        return defaultLevel;
    }
}
