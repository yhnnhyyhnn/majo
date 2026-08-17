package com.agent.coding.mcp;

import com.agent.coding.skill.SkillStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP client card + credential persistence.
 *
 * <p>Stores one DriverCard JSON per client under {@code mcp/cards/} and
 * credential records under {@code mcp/credentials/} beneath the working
 * directory, reusing the same file-lock + atomic-write pattern as
 * {@link SkillStore}.</p>
 *
 * <p>Port of DriverConfigService (card_path / load_card / save_card /
 * list_cards / credential_store) restricted to MCP protocol.</p>
 */
public class McpStore {

    private static final Logger log = LoggerFactory.getLogger(McpStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Well-known sub-dirs under the working dir. */
    private static final String MCP_DIR = "mcp";

    public static final String SCHEMA_VERSION = "mcp-card.v1";

    public static Path cardsDir() {
        return SkillStore.WORKING_DIR.resolve(MCP_DIR).resolve("cards");
    }

    public static Path credentialsDir() {
        return SkillStore.WORKING_DIR.resolve(MCP_DIR).resolve("credentials");
    }

    /** mcp/{client_key} */
    public static String mcpCredentialRef(String clientKey) {
        return "mcp/" + clientKey;
    }

    /** mcp/{client_key}/oauth */
    public static String mcpOauthCredentialRef(String clientKey) {
        return "mcp/" + clientKey + "/oauth";
    }

    // ------------------------------------------------------------------
    // Card paths
    // ------------------------------------------------------------------

    public static Path cardPath(String clientKey) {
        return cardsDir().resolve(clientKey + ".json");
    }

    public static boolean cardExists(String clientKey) {
        return Files.isRegularFile(cardPath(clientKey));
    }

    /** Load a card; null when it does not exist. */
    public static Map<String, Object> loadCardOrNull(String clientKey) {
        Path path = cardPath(clientKey);
        if (!Files.isRegularFile(path)) return null;
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("schema_version", SCHEMA_VERSION);
        return readJsonQuiet(path, defaults);
    }

    /** Load a card, throwing 404 when missing. */
    public static Map<String, Object> loadCard(String clientKey) {
        Map<String, Object> card = loadCardOrNull(clientKey);
        if (card == null) {
            throw new McpException(404, "MCP client '" + clientKey + "' not found");
        }
        return card;
    }

    public static void saveCard(String clientKey, Map<String, Object> card) {
        Map<String, Object> payload = card == null ? new LinkedHashMap<>() : card;
        payload.put("schema_version", SCHEMA_VERSION);
        SkillStore.writeJsonAtomic(cardPath(clientKey), payload);
    }

    public static void deleteCard(String clientKey) {
        try {
            Files.deleteIfExists(cardPath(clientKey));
        } catch (IOException e) {
            log.warn("Failed to delete MCP card for '{}': {}", clientKey, e.getMessage());
        }
    }

    /** List all stored MCP client keys, sorted. */
    public static List<String> listClientKeys() {
        List<String> keys = new ArrayList<>();
        try (Stream<Path> stream = Files.list(cardsDir())) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".json")) {
                    keys.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        } catch (IOException e) {
            // dir may not exist yet
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    // ------------------------------------------------------------------
    // Credential records
    // ------------------------------------------------------------------

    private static Path credentialPath(String ref) {
        return credentialsDir().resolve(ref.replace('/', '_') + ".json");
    }

    public static Map<String, Object> loadCredentialOrNull(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        Path path = credentialPath(ref);
        if (!Files.isRegularFile(path)) return null;
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("ref", ref);
        return readJsonQuiet(path, defaults);
    }

    public static void saveCredential(String ref, Map<String, Object> record) {
        if (ref == null || ref.isEmpty()) return;
        Map<String, Object> payload = record == null ? new LinkedHashMap<>() : record;
        payload.put("ref", ref);
        SkillStore.writeJsonAtomic(credentialPath(ref), payload);
    }

    public static void deleteCredential(String ref) {
        if (ref == null || ref.isEmpty()) return;
        try {
            Files.deleteIfExists(credentialPath(ref));
        } catch (IOException e) {
            log.warn("Failed to delete MCP credential '{}': {}", ref, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // JSON I/O helpers (lock + atomic write, mirrors SkillStore)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonQuiet(Path path, Map<String, Object> defaultPayload) {
        try {
            if (!Files.isRegularFile(path)) return new LinkedHashMap<>(defaultPayload);
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (text.isBlank()) return new LinkedHashMap<>(defaultPayload);
            Object parsed = MAPPER.readValue(text, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() != null) result.put(String.valueOf(e.getKey()), e.getValue());
                }
                return result;
            }
            return new LinkedHashMap<>(defaultPayload);
        } catch (Exception e) {
            log.warn("Malformed JSON in {}, resetting to default", path);
            return new LinkedHashMap<>(defaultPayload);
        }
    }
}
