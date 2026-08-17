package com.agent.coding.loop;

import java.util.Map;

/**
 * Result from a stop handler / gate evaluation
 * loop/gates/base.py StopHandlerResult. When action is
 * INTERRUPT_AND_CONTINUE, continuationMessage is injected as the next user
 * turn to keep the agent running.
 */
public class StopHandlerResult {

    public final StopAction action;
    public final String continuationMessage;
    public final String reason;
    public final Map<String, Object> metadata;

    public StopHandlerResult(StopAction action, String continuationMessage, String reason,
                             Map<String, Object> metadata) {
        this.action = action;
        this.continuationMessage = continuationMessage;
        this.reason = reason;
        this.metadata = metadata;
    }

    public static StopHandlerResult terminate(String reason) {
        return new StopHandlerResult(StopAction.TERMINATE, "", reason, null);
    }

    public static StopHandlerResult continueWith(String message, String reason) {
        return new StopHandlerResult(StopAction.INTERRUPT_AND_CONTINUE, message, reason, null);
    }

    public static StopHandlerResult bypass() {
        return new StopHandlerResult(StopAction.BYPASS, "", "", null);
    }

    public boolean isTerminate() {
        return action == StopAction.TERMINATE;
    }

    public boolean isContinue() {
        return action == StopAction.INTERRUPT_AND_CONTINUE;
    }
}
