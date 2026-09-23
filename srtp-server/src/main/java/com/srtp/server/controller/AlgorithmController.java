package com.srtp.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.srtp.server.model.CustomAlgorithm;
import com.srtp.server.model.HistoryRecord;
import com.srtp.server.repository.AlgoParamSeedRepository;
import com.srtp.server.repository.CustomAlgorithmRepository;
import com.srtp.server.service.AlgorithmService;
import com.srtp.server.service.HistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/algorithm")
public class AlgorithmController {

    private static final Logger log = LoggerFactory.getLogger(AlgorithmController.class);
    private final AlgorithmService algoService;
    private final HistoryService historyService;
    private final CustomAlgorithmRepository customAlgoRepo;
    private final AlgoParamSeedRepository algoSeedRepo;

    public AlgorithmController(AlgorithmService algoService, HistoryService historyService,
                               CustomAlgorithmRepository customAlgoRepo,
                               AlgoParamSeedRepository algoSeedRepo) {
        this.algoService = algoService;
        this.historyService = historyService;
        this.customAlgoRepo = customAlgoRepo;
        this.algoSeedRepo = algoSeedRepo;
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return ((AuthController.UserPrincipal) auth.getPrincipal()).userId();
    }

    @GetMapping("/list")
    public List<Map<String, Object>> listAlgorithms() {
        Long userId = getUserId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : AlgorithmService.ALL_ALGORITHMS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", AlgorithmService.ALGO_LABELS.getOrDefault(id, id));
            m.put("is_custom", false);
            list.add(m);
        }
        if (userId != null) {
            for (CustomAlgorithm ca : customAlgoRepo.findAllByUserId(userId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ca.getAlgoId());
                m.put("label", "[自定义] " + ca.getLabel());
                m.put("is_custom", true);
                list.add(m);
            }
        }
        return list;
    }

    @GetMapping("/params")
    public Map<String, Object> getParams(@RequestParam(defaultValue = "8") int cityCount) {
        Long userId = getUserId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city_count", cityCount);
        List<Map<String, Object>> algoParams = new ArrayList<>();
        for (String id : AlgorithmService.ALL_ALGORITHMS) {
            algoParams.add(AlgorithmService.getParamDefs(id, cityCount));
        }
        if (userId != null) {
            for (CustomAlgorithm ca : customAlgoRepo.findAllByUserId(userId)) {
                algoParams.add(customAlgoToParamDefs(ca));
            }
        }
        result.put("algorithms", algoParams);
        return result;
    }

    private Map<String, Object> customAlgoToParamDefs(CustomAlgorithm ca) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ca.getAlgoId());
        result.put("label", "[自定义] " + ca.getLabel());
        result.put("is_custom", true);
        List<Map<String, Object>> params = new ArrayList<>();
        try {
            JsonNode specs = AlgorithmService.MAPPER.readTree(ca.getParamSpecs() == null ? "[]" : ca.getParamSpecs());
            if (specs.isArray()) {
                for (JsonNode s : specs) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("key", s.path("key").asText());
                    p.put("label", s.path("label").asText());
                    p.put("type", s.path("type").asText("int"));
                    JsonNode dv = s.path("default");
                    if (dv.isNumber()) p.put("default", dv.asDouble());
                    else if (!dv.isMissingNode() && !dv.isNull()) p.put("default", dv.asText());
                    else p.put("default", "");
                    params.add(p);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse param_specs for custom algo {}: {}", ca.getAlgoId(), e.getMessage());
        }
        result.put("params", params);
        result.put("defaults", Map.of());
        return result;
    }

    @PostMapping("/run")
    public Map<String, Object> runAlgorithms(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        @SuppressWarnings("unchecked")
        List<String> algoIds = (List<String>) body.getOrDefault("algorithms", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cities = (List<Map<String, Object>>) body.getOrDefault("cities", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> algosParams = (Map<String, Map<String, Object>>) body.getOrDefault("params", Map.of());
        int baseSeed = parseNumber(body.getOrDefault("seed", 42)).intValue();
        int citySeed = parseNumber(body.getOrDefault("city_seed", baseSeed)).intValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> algoSeedsRaw = (Map<String, Object>) body.getOrDefault("algo_seeds", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> weightsMap = (Map<String, Object>) body.getOrDefault("weights", Map.of());

        if (algoIds.isEmpty() || cities.isEmpty()) {
            return Map.of("ok", false, "error", "请选择算法并生成城市数据");
        }

        // Load custom algorithms for this user (one-shot)
        Map<String, CustomAlgorithm> customMap = new HashMap<>();
        if (userId != null) {
            for (CustomAlgorithm ca : customAlgoRepo.findAllByUserId(userId)) {
                customMap.put(ca.getAlgoId(), ca);
            }
        }

        int cityCount = cities.size();
        List<Map<String, Object>> rows = new ArrayList<>();

        // Bounded thread pool: max 2x CPU cores, queue 100, caller-runs on overflow
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = new ThreadPoolExecutor(
                Math.max(4, cores), Math.max(8, cores * 2),
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.CallerRunsPolicy());
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        List<Map<String, Object>> mergedParamsList = new ArrayList<>();

        for (int i = 0; i < algoIds.size(); i++) {
            final String aid = algoIds.get(i);
            final int seed = baseSeed + i * 100;
            final Map<String, Object> userParams = algosParams.getOrDefault(aid, Map.of());
            final Map<String, Object> mp = AlgorithmService.buildParams(aid, cityCount, userParams);
            mergedParamsList.add(mp);
            // Build custom info if this is a custom algorithm
            final Map<String, String> customInfo;
            CustomAlgorithm ca = customMap.get(aid);
            if (ca != null) {
                customInfo = new HashMap<>();
                customInfo.put("code", ca.getCode());
                customInfo.put("iter_key", ca.getIterKey() == null ? "" : ca.getIterKey());
                customInfo.put("pop_key", ca.getPopKey() == null ? "" : ca.getPopKey());
                customInfo.put("label", ca.getLabel());
            } else {
                customInfo = null;
            }
            futures.add(executor.submit(() -> algoService.runAlgorithm(aid, cities, mp, seed, weightsMap, userId, customInfo)));
        }
        executor.shutdown();

        for (int i = 0; i < algoIds.size(); i++) {
            try {
                Map<String, Object> result = futures.get(i).get(300, TimeUnit.SECONDS);
                if (result.containsKey("error")) {
                    rows.add(result);
                } else {
                    String algoId = (String) result.get("algorithm_id");
                    if (userId != null) {
                        HistoryRecord record = new HistoryRecord();
                        record.setAlgorithmId(algoId);
                        record.setCityCount(cities.size());
                        record.setInputText(String.valueOf(body.getOrDefault("input_text", "")));
                        record.setCitiesJson(AlgorithmService.MAPPER.writeValueAsString(cities));
                        record.setSeed(baseSeed + i * 100);
                        // Auto-match algo_seed from saved param seeds in DB
                        int as = resolveAlgoSeed(userId, algoId, mergedParamsList.get(i));
                        record.setAlgoSeed(as);
                        record.setCitySeed(citySeed);
                        record.setParams(AlgorithmService.MAPPER.writeValueAsString(mergedParamsList.get(i)));
                        record.setBestDistance(((Number) result.get("best_distance")).doubleValue());
                        record.setBestPath(AlgorithmService.MAPPER.writeValueAsString(result.get("best_path")));
                        record.setBestPathText((String) result.get("best_path_text"));
                        record.setElapsedMs(((Number) result.get("elapsed_ms")).intValue());
                        Object cs = result.get("complexity_score");
                        if (cs instanceof Number) record.setComplexityScore(((Number) cs).longValue());
                        Object cl = result.get("complexity_label");
                        if (cl instanceof String) record.setComplexityLabel((String) cl);
                        record.setUserId(userId);
                        record.setCreatedAt(LocalDateTime.now());
                        long rid = historyService.insertRecord(record);
                        result.put("record_id", rid);
                    }
                    rows.add(result);
                }
            } catch (Exception e) {
                log.error("Algorithm run failed", e);
                Map<String, Object> err = new HashMap<>();
                err.put("algorithm_id", algoIds.get(i));
                CustomAlgorithm ca = customMap.get(algoIds.get(i));
                String label = ca != null ? "[自定义] " + ca.getLabel()
                        : AlgorithmService.ALGO_LABELS.getOrDefault(algoIds.get(i), algoIds.get(i));
                err.put("algorithm_label", label);
                err.put("error", e.getMessage());
                rows.add(err);
            }
        }

        return Map.of("ok", true, "results", rows);
    }

    private int resolveAlgoSeed(Long userId, String algoId, Map<String, Object> params) {
        if (userId == null || params == null || params.isEmpty()) return 0;
        try {
            TreeMap<String, Object> normalized = AlgorithmService.normalizeParams(algoId, params);
            String paramsJson = AlgorithmService.MAPPER.writeValueAsString(normalized);
            var seed = algoSeedRepo.findByParams(userId, algoId, paramsJson);
            return seed != null ? seed.getSeedNumber() : 0;
        } catch (Exception e) {
            log.debug("Failed to resolve algo seed for {}: {}", algoId, e.getMessage());
            return 0;
        }
    }

    private static Number parseNumber(Object val) {
        if (val instanceof Number n) return n;
        if (val instanceof String s) {
            try { return s.contains(".") ? Double.parseDouble(s) : Integer.parseInt(s); }
            catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
