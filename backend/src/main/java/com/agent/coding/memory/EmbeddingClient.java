package com.agent.coding.memory;

import java.util.List;

/**
 * Embedding transport for the {@code embedding} memory backend (ADR-0015).
 * Abstracted so tests can supply deterministic vectors; the production
 * implementation speaks the OpenAI-compatible {@code /embeddings} protocol.
 */
public interface EmbeddingClient {

    /** Endpoint + credentials for one embedding call. */
    record Endpoint(String baseUrl, String apiKey, String model) {

        /** A model is the only hard requirement; base URL defaults to the
         *  OpenAI endpoint inside the client, and some providers need no key. */
        public boolean isComplete() {
            return model != null && !model.isBlank();
        }
    }

    /**
     * Embed the inputs in order. Implementations may batch internally but
     * MUST return exactly one vector per input, in the same order.
     */
    List<float[]> embed(Endpoint endpoint, List<String> inputs) throws Exception;
}
