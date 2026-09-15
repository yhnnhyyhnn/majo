package com.agent.coding.memory;

import java.util.Map;

/**
 * One memory recall result — the minimal common surface every backend
 * returns regardless of storage technology.
 */
public record MemoryHit(String source, String snippet, double score,
                        Map<String, Object> metadata) {}
