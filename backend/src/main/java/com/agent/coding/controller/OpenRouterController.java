package com.agent.coding.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter model endpoints,
 * (openrouter section). Model data is fetched live from the public
 * OpenRouter models API; no API key is required to list models.
 */
@RestController
@RequestMapping("/api/models/openrouter")
@CrossOrigin(origins = "*")
public class OpenRouterController {

    private static final String MODELS_URL = "https://openrouter.ai/api/v1/models";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private List<Map<String, Object>> fetchModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(MODELS_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Majo/0.1")
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return List.of();
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode data = root.path("data");
            List<Map<String, Object>> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("name", node.path("name").asText(id));
                m.put("supports_multimodal", node.path("architecture").path("input_modalities").isArray()
                        && containsModality(node.path("architecture").path("input_modalities"), "image"));
                m.put("supports_image", node.path("architecture").path("input_modalities").isArray()
                        && containsModality(node.path("architecture").path("input_modalities"), "image"));
                m.put("supports_video", node.path("architecture").path("input_modalities").isArray()
                        && containsModality(node.path("architecture").path("input_modalities"), "video"));
                m.put("probe_source", "openrouter");
                m.put("is_free", isFree(node.path("pricing")));
                m.put("provider", id.contains("/") ? id.substring(0, id.indexOf('/')) : "");
                m.put("input_modalities", modalityList(node.path("architecture").path("input_modalities")));
                m.put("output_modalities", modalityList(node.path("architecture").path("output_modalities")));
                m.put("pricing", pricingMap(node.path("pricing")));
                models.add(m);
            }
            return models;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean containsModality(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> modalityList(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode n : array) {
            result.add(n.asText());
        }
        return result;
    }

    private static boolean isFree(JsonNode pricing) {
        boolean any = false;
        for (JsonNode n : pricing) {
            try {
                if (Double.parseDouble(n.asText()) != 0) {
                    return false;
                }
                any = true;
            } catch (NumberFormatException ignored) {
            }
        }
        return any;
    }

    private static Map<String, String> pricingMap(JsonNode pricing) {
        Map<String, String> result = new LinkedHashMap<>();
        pricing.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        return result;
    }

    @GetMapping("/series")
    public Map<String, Object> series() {
        List<String> providers = new ArrayList<>();
        for (Map<String, Object> model : fetchModels()) {
            String provider = String.valueOf(model.get("provider"));
            if (!provider.isBlank() && !providers.contains(provider)) {
                providers.add(provider);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("series", providers);
        return result;
    }

    @PostMapping("/discover-extended")
    public Map<String, Object> discoverExtended(@RequestBody(required = false) Map<String, Object> body) {
        List<Map<String, Object>> models = fetchModels();
        List<String> providers = new ArrayList<>();
        for (Map<String, Object> model : models) {
            String provider = String.valueOf(model.get("provider"));
            if (!provider.isBlank() && !providers.contains(provider)) {
                providers.add(provider);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", !models.isEmpty());
        result.put("models", models);
        result.put("providers", providers);
        result.put("total_count", models.size());
        return result;
    }

    @PostMapping("/models/filter")
    public Map<String, Object> filter(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> all = fetchModels();
        List<String> providers = stringList(body.get("providers"));
        List<String> inputModalities = stringList(body.get("input_modalities"));
        Double maxInputPrice = body.get("max_input_price") == null
                ? null : ((Number) body.get("max_input_price")).doubleValue();

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> model : all) {
            String provider = String.valueOf(model.get("provider"));
            if (!providers.isEmpty() && !providers.contains(provider)) {
                continue;
            }
            if (!inputModalities.isEmpty()) {
                List<String> modelInputs = castList(model.get("input_modalities"));
                boolean hasAny = false;
                for (String mod : inputModalities) {
                    if (modelInputs.contains(mod)) {
                        hasAny = true;
                        break;
                    }
                }
                if (!hasAny) {
                    continue;
                }
            }
            if (maxInputPrice != null) {
                Map<String, String> pricing = castPricing(model.get("pricing"));
                try {
                    double input = Double.parseDouble(pricing.getOrDefault("prompt", "0"));
                    if (input > maxInputPrice) {
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            filtered.add(model);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("models", filtered);
        result.put("total_count", filtered.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return o instanceof List<?> list ? (List<String>) (List<?>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castPricing(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, String>) (Map<?, ?>) m : Map.of();
    }
}
