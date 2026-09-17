package com.agent.coding.channel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LastContactStore (heartbeat target=last, ADR-0013): records the most
 * recent channel contact and survives store re-creation (JSON persistence).
 */
class LastContactStoreTest {

    @Test
    void recordAndReadLatest() {
        LastContactStore store = new LastContactStore();
        store.record("telegram", "12345", "alice");
        Map<String, Object> latest = store.latest();
        assertEquals("telegram", latest.get("channel"));
        assertEquals("12345", latest.get("to"));
        assertEquals("alice", latest.get("user_id"));

        // A newer contact replaces the previous one.
        store.record("slack", "C6789", "bob");
        assertEquals("slack", store.latest().get("channel"));
        assertEquals("C6789", store.latest().get("to"));
    }

    @Test
    void blankEntriesAreIgnored() {
        LastContactStore store = new LastContactStore();
        store.record("", "12345", "alice");
        store.record("telegram", "  ", "alice");
        store.record(null, "12345", "alice");
        assertNull(store.latest(), "no contact recorded for blank input");
    }

    @Test
    void persistenceSurvivesRecreation() {
        LastContactStore first = new LastContactStore();
        first.record("discord", "dm-42", "carol");

        LastContactStore second = new LastContactStore();
        Map<String, Object> latest = second.latest();
        assertEquals("discord", latest.get("channel"));
        assertEquals("dm-42", latest.get("to"));
        assertTrue(Double.parseDouble(String.valueOf(latest.get("updated_at"))) > 0);
    }
}
