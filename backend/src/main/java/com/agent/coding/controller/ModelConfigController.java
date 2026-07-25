package com.agent.coding.controller;

import com.agent.coding.entity.ModelConfigEntity;
import com.agent.coding.repository.ModelConfigRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ModelConfigController {

    private final ModelConfigRepository repository;

    public ModelConfigController(ModelConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/models")
    public List<ModelConfigEntity> list() {
        return repository.findAll();
    }

    @PostMapping("/models")
    public ModelConfigEntity create(@RequestBody Map<String, String> body) {
        var entity = new ModelConfigEntity();
        entity.setName(body.getOrDefault("name", "Unnamed"));
        entity.setApiKey(body.getOrDefault("apiKey", ""));
        entity.setBaseUrl(body.getOrDefault("baseUrl", "https://api.openai.com/v1"));
        entity.setModelName(body.getOrDefault("modelName", "gpt-4o-mini"));
        return repository.save(entity);
    }

    @PutMapping("/models/{id}")
    public ModelConfigEntity update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return repository.findById(id).map(entity -> {
            if (body.containsKey("name")) entity.setName(body.get("name"));
            if (body.containsKey("apiKey")) entity.setApiKey(body.get("apiKey"));
            if (body.containsKey("baseUrl")) entity.setBaseUrl(body.get("baseUrl"));
            if (body.containsKey("modelName")) entity.setModelName(body.get("modelName"));
            return repository.save(entity);
        }).orElseThrow(() -> new RuntimeException("Model not found: " + id));
    }

    @DeleteMapping("/models/{id}")
    public Map<String, String> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return Map.of("status", "ok");
    }
}
