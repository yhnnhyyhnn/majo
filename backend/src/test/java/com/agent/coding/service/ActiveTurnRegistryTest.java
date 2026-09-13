package com.agent.coding.service;

import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Tests for the console stop registry (#7349 stop-cancellation semantics). */
class ActiveTurnRegistryTest {

    @Test
    void stopInterruptsRegisteredAgent() {
        ActiveTurnRegistry registry = new ActiveTurnRegistry();
        HarnessAgent agent = mock(HarnessAgent.class);
        registry.register("chat-1", agent);
        assertTrue(registry.isActive("chat-1"));

        assertTrue(registry.stop("chat-1"));
        verify(agent).interrupt();
        assertFalse(registry.isActive("chat-1"));
    }

    @Test
    void stopUnknownChatReturnsFalse() {
        ActiveTurnRegistry registry = new ActiveTurnRegistry();
        assertFalse(registry.stop("no-such-chat"));
        assertFalse(registry.stop(null));
        assertFalse(registry.stop(""));
    }

    @Test
    void registerIgnoresBlankChatIdOrNullAgent() {
        ActiveTurnRegistry registry = new ActiveTurnRegistry();
        registry.register(null, mock(HarnessAgent.class));
        registry.register("", mock(HarnessAgent.class));
        registry.register("chat-2", null);
        assertFalse(registry.isActive("chat-2"));
        assertEquals(0, registry.activeChatIds().size());
    }

    @Test
    void unregisterRemovesWithoutInterrupting() {
        ActiveTurnRegistry registry = new ActiveTurnRegistry();
        HarnessAgent agent = mock(HarnessAgent.class);
        registry.register("chat-3", agent);
        registry.unregister("chat-3");
        assertFalse(registry.isActive("chat-3"));
        // A completed turn is not interrupted on cleanup.
        assertTrue(registry.stop("chat-3") == false);
    }
}
