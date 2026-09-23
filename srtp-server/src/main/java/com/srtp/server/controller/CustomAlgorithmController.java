package com.srtp.server.controller;

import com.srtp.server.model.CustomAlgorithm;
import com.srtp.server.repository.CustomAlgorithmRepository;
import com.srtp.server.service.AlgorithmService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/custom-algo")
public class CustomAlgorithmController {

    private final CustomAlgorithmRepository customAlgoRepo;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public CustomAlgorithmController(CustomAlgorithmRepository customAlgoRepo) {
        this.customAlgoRepo = customAlgoRepo;
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
        return customAlgoRepo.findAllByUserId(userId).stream().map(this::toMap).collect(Collectors.toList());
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        String algoId = String.valueOf(body.get("algo_id")).trim();
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        String code = String.valueOf(body.getOrDefault("code", ""));
        String paramSpecs = String.valueOf(body.getOrDefault("param_specs", "[]"));
        String iterKey = body.get("iter_key") == null ? null : String.valueOf(body.get("iter_key")).trim();
        String popKey = body.get("pop_key") == null ? null : String.valueOf(body.get("pop_key")).trim();
        if (iterKey != null && iterKey.isEmpty()) iterKey = null;
        if (popKey != null && popKey.isEmpty()) popKey = null;

        if (algoId.isEmpty()) return Map.of("ok", false, "error", "算法标识不能为空");
        if (label.isEmpty()) return Map.of("ok", false, "error", "算法名称不能为空");
        if (code.trim().isEmpty()) return Map.of("ok", false, "error", "算法代码不能为空");

        // forbid reserved algo ids (built-in ones)
        for (String reserved : AlgorithmService.ALL_ALGORITHMS) {
            if (reserved.equalsIgnoreCase(algoId)) {
                return Map.of("ok", false, "error", "算法标识 '" + algoId + "' 与内置算法冲突，请换一个");
            }
        }

        customAlgoRepo.upsert(userId, algoId, label, code, paramSpecs, iterKey, popKey);
        return Map.of("ok", true);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        String algoId = String.valueOf(body.get("algo_id"));
        customAlgoRepo.delete(userId, algoId);
        return Map.of("ok", true);
    }

    private Map<String, Object> toMap(CustomAlgorithm a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("algo_id", a.getAlgoId());
        m.put("label", a.getLabel());
        m.put("code", a.getCode());
        m.put("param_specs", a.getParamSpecs());
        m.put("iter_key", a.getIterKey() == null ? "" : a.getIterKey());
        m.put("pop_key", a.getPopKey() == null ? "" : a.getPopKey());
        m.put("updated_at", a.getUpdatedAt() != null ? a.getUpdatedAt().format(DATE_FMT) : "");
        return m;
    }
}
