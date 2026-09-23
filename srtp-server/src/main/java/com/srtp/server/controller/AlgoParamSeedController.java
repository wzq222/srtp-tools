package com.srtp.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.srtp.server.model.AlgoParamSeed;
import com.srtp.server.repository.AlgoParamSeedRepository;
import com.srtp.server.service.AlgorithmService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/algo-seed")
public class AlgoParamSeedController {

    private final AlgoParamSeedRepository repo;

    public AlgoParamSeedController(AlgoParamSeedRepository repo) {
        this.repo = repo;
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return ((AuthController.UserPrincipal) auth.getPrincipal()).userId();
    }

    /** List all param seeds for a given algorithm. */
    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam String algorithmId) {
        Long userId = getUserId();
        if (userId == null) return List.of();
        // Build key→Chinese label map from PARAM_SPECS
        java.util.Map<String, String> labelMap = new java.util.HashMap<>();
        java.util.List<Map<String, String>> specs = AlgorithmService.PARAM_SPECS.get(algorithmId);
        if (specs != null) {
            for (Map<String, String> s : specs) {
                labelMap.put(s.get("key"), s.get("label"));
            }
        }
        List<AlgoParamSeed> seeds = repo.findAllByUserIdAndAlgorithm(userId, algorithmId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AlgoParamSeed s : seeds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("seed_number", s.getSeedNumber());
            m.put("params", s.getParams());
            m.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            // Build display string with Chinese labels
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> pm = AlgorithmService.MAPPER.readValue(s.getParams(), Map.class);
                List<String> displayParts = new ArrayList<>();
                for (Map.Entry<String, Object> e : pm.entrySet()) {
                    String label = labelMap.getOrDefault(e.getKey(), e.getKey());
                    displayParts.add(label + "=" + e.getValue());
                }
                m.put("params_display", String.join("，", displayParts));
            } catch (JsonProcessingException e) {
                m.put("params_display", s.getParams());
            }
            result.add(m);
        }
        return result;
    }

    /** Add a new param seed. Auto-generates seed number. Checks for duplicate params. */
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        String algorithmId = (String) body.get("algorithm_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
        String paramsJson;
        try {
            // Normalize to consistent types (int→Integer, float→Double)
            TreeMap<String, Object> normalized = AlgorithmService.normalizeParams(algorithmId, params);
            paramsJson = AlgorithmService.MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            return Map.of("ok", false, "error", "参数序列化失败");
        }
        // Check duplicate
        AlgoParamSeed existing = repo.findByParams(userId, algorithmId, paramsJson);
        if (existing != null) {
            return Map.of("ok", false, "error", "该参数已存在，对应种子号为 " + existing.getSeedNumber());
        }
        int seedNum = repo.generateUniqueSeedNumber(userId, algorithmId);
        AlgoParamSeed seed = new AlgoParamSeed();
        seed.setUserId(userId);
        seed.setAlgorithmId(algorithmId);
        seed.setSeedNumber(seedNum);
        seed.setParams(paramsJson);
        seed.setCreatedAt(LocalDateTime.now());
        long id = repo.insert(seed);
        return Map.of("ok", true, "id", id, "seed_number", seedNum);
    }

    /** Update params of an existing seed. Seed number stays the same. */
    @PostMapping("/update")
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        long id = ((Number) body.get("id")).longValue();
        String algorithmId = (String) body.get("algorithm_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
        String paramsJson;
        try {
            TreeMap<String, Object> normalized = AlgorithmService.normalizeParams(algorithmId, params);
            paramsJson = AlgorithmService.MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            return Map.of("ok", false, "error", "参数序列化失败");
        }
        // Check duplicate with other seeds
        AlgoParamSeed existing = repo.findByParams(userId, algorithmId, paramsJson);
        if (existing != null && existing.getId() != id) {
            return Map.of("ok", false, "error", "该参数已存在，对应种子号为 " + existing.getSeedNumber());
        }
        repo.updateParams(id, paramsJson);
        return Map.of("ok", true);
    }

    /** Delete a param seed. */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        long id = ((Number) body.get("id")).longValue();
        repo.delete(id, userId);
        return Map.of("ok", true);
    }

    /** Get a specific seed's params by seed number. */
    @GetMapping("/get")
    public Map<String, Object> get(@RequestParam String algorithmId, @RequestParam int seedNumber) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        AlgoParamSeed seed = repo.findBySeedNumber(userId, algorithmId, seedNumber);
        if (seed == null) return Map.of("ok", false, "error", "种子不存在");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> pm = AlgorithmService.MAPPER.readValue(seed.getParams(), Map.class);
            return Map.of("ok", true, "params", pm, "seed_number", seed.getSeedNumber());
        } catch (JsonProcessingException e) {
            return Map.of("ok", false, "error", "参数解析失败");
        }
    }
}
