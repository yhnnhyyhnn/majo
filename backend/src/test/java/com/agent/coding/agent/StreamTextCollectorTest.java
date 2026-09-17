package com.agent.coding.agent;

import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the shared stream delta collector: text and thinking
 * accumulation across interleaved events, and ignoring anything else.
 */
class StreamTextCollectorTest {

    @Test
    void collectsTextAndThinkingDeltas() {
        StreamTextCollector collector = new StreamTextCollector();
        collector.accept(new TextBlockDeltaEvent("r", "b1", "你好"));
        collector.accept(new ThinkingBlockDeltaEvent("r", "b2", "思考"));
        collector.accept(new TextBlockDeltaEvent("r", "b1", "，世界"));
        collector.accept(new ThinkingBlockDeltaEvent("r", "b2", "中…"));

        assertEquals("你好，世界", collector.text());
        assertEquals("思考中…", collector.thinking());
    }

    @Test
    void ignoresNonDeltaEvents() {
        StreamTextCollector collector = new StreamTextCollector();
        collector.accept("a plain string");
        collector.accept(new Object());
        collector.accept(null);

        assertTrue(collector.text().isEmpty());
        assertTrue(collector.thinking().isEmpty());
    }

    @Test
    void emptyByDefault() {
        StreamTextCollector collector = new StreamTextCollector();
        assertEquals("", collector.text());
        assertEquals("", collector.thinking());
    }
}
