package com.srtp.server.controller;

import com.srtp.server.model.SeedData;
import com.srtp.server.repository.SeedRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/seed")
public class SeedController {

    private final SeedRepository seedRepo;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public SeedController(SeedRepository seedRepo) {
        this.seedRepo = seedRepo;
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return ((AuthController.UserPrincipal) auth.getPrincipal()).userId();
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Long userId = getUserId();
        if (userId == null) return List.of();
        return seedRepo.findAllByUserId(userId).stream().map(s -> toMap(s)).collect(Collectors.toList());
    }

    @GetMapping("/info")
    public Map<String, Object> info(@RequestParam int seed) {
        Long userId = getUserId();
        if (userId == null) return Map.of("found", false);
        SeedData sd = seedRepo.findByUserAndSeed(userId, seed);
        if (sd == null) return Map.of("found", false);
        return Map.of("found", true, "data", toMap(sd));
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        int seed = ((Number) body.get("seed")).intValue();
        int cityCount = ((Number) body.get("city_count")).intValue();
        String cityData = (String) body.getOrDefault("city_data", "");
        String weights = (String) body.getOrDefault("weights", "");
        seedRepo.upsert(userId, seed, cityCount, cityData, weights);
        return Map.of("ok", true);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        int seed = ((Number) body.get("seed")).intValue();
        seedRepo.delete(userId, seed);
        return Map.of("ok", true);
    }

    private static Map<String, Object> toMap(SeedData s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("seed", s.getSeed());
        m.put("city_count", s.getCityCount());
        m.put("city_data", s.getCityData());
        m.put("weights", s.getWeights());
        m.put("updated_at", s.getUpdatedAt() != null ? s.getUpdatedAt().format(DATE_FMT) : "");
        return m;
    }
}
