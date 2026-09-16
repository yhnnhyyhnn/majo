package com.agent.coding.memory;

import java.util.Optional;

/**
 * Extraction step in front of {@link MemoryBackend#remember} (ADR-0014):
 * distills accumulated conversation turns into durable facts worth keeping
 * long-term. Empty = the batch has no lasting value. Implementations must
 * be cheap and may throw — callers fall back to the raw excerpt.
 */
public interface MemorySummarizer {

    /** Extract durable facts from one accumulated memory batch. */
    Optional<String> summarize(String agentId, String content);
}
