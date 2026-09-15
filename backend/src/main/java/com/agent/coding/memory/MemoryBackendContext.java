package com.agent.coding.memory;

import java.nio.file.Path;
import java.util.Map;

/**
 * Stable construction context handed to a {@link MemoryBackend} on start.
 * Mirrors QwenPaw's MemoryBackendContext (ADR-0008).
 */
public record MemoryBackendContext(
        String agentId,
        Path workspace,
        Map<String, Object> backendConfig,
        String language) {}
