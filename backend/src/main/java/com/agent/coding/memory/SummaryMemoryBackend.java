package com.agent.coding.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keyword backend + LLM extraction in front of remember() (ADR-0014), the
 * majo counterpart of QwenPaw's ReMe auto_memory job. Retrieval, index and
 * lifecycle are inherited unchanged (same memory/ directory layout); only
 * what gets written differs: the {@link MemorySummarizer} distills the
 * batch into durable facts first. Extraction failure or "nothing worth
 * keeping" falls back to the raw excerpt — an unavailable LLM degrades to
 * the plain keyword backend instead of losing turns. Select with
 * {@code memory_manager_backend="summary"}.
 */
@Component
public class SummaryMemoryBackend extends KeywordMemoryBackend {

    static final String ID = "summary";

    private static final Logger log = LoggerFactory.getLogger(SummaryMemoryBackend.class);

    private final MemorySummarizer summarizer;

    public SummaryMemoryBackend(MemorySummarizer summarizer) {
        this.summarizer = summarizer;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void remember(String content, Map<String, Object> metadata) {
        String agentId = metadata == null ? null : String.valueOf(metadata.get("agent_id"));
        String extracted = null;
        try {
            extracted = summarizer
                    .summarize(agentId == null || "null".equals(agentId) ? "default" : agentId, content)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[memory:{}] summarizer failed, falling back to raw excerpt: {}",
                    ID, e.getMessage());
        }
        Map<String, Object> enriched = new LinkedHashMap<>(
                metadata == null ? Map.of() : metadata);
        enriched.put("extraction", extracted != null ? "llm_summary" : "raw_excerpt");
        super.remember(extracted != null ? extracted : content, enriched);
    }
}
