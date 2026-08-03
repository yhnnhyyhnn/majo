package com.agent.coding.controller;

import com.agent.coding.dto.EnvVarDto;
import com.agent.coding.entity.EnvVarEntity;
import com.agent.coding.repository.EnvVarRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class EnvsController {

    private final EnvVarRepository repo;

    public EnvsController(EnvVarRepository repo) { this.repo = repo; }

    @GetMapping("/envs")
    public List<EnvVarDto> list() {
        return repo.findAll().stream()
            .sorted(Comparator.comparing(EnvVarEntity::getKey))
            .map(e -> new EnvVarDto(e.getKey(), e.getValue()))
            .toList();
    }

    @PutMapping("/envs")
    @Transactional
    public List<EnvVarDto> save(@RequestBody Map<String, String> body) {
        repo.deleteAll();
        body.forEach((k, v) -> {
            if (!k.isBlank()) repo.save(new EnvVarEntity(k.trim(), v));
        });
        return list();
    }

    @DeleteMapping("/envs/{key}")
    @Transactional
    public List<EnvVarDto> delete(@PathVariable String key) {
        repo.deleteById(key);
        return list();
    }
}
