package com.srtp.server.controller;

import com.srtp.server.model.HistoryRecord;
import com.srtp.server.repository.AlgoParamSeedRepository;
import com.srtp.server.service.AlgorithmService;
import com.srtp.server.service.HistoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final AlgoParamSeedRepository algoSeedRepo;
    private final com.srtp.server.repository.HistoryRepository historyRepo;
    private final AlgorithmService algoService;
    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public HistoryController(HistoryService historyService, AlgoParamSeedRepository algoSeedRepo,
                             com.srtp.server.repository.HistoryRepository historyRepo,
                             AlgorithmService algoService) {
        this.historyService = historyService;
        this.algoSeedRepo = algoSeedRepo;
        this.historyRepo = historyRepo;
        this.algoService = algoService;
    }

    private Long getUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return ((AuthController.UserPrincipal) auth.getPrincipal()).userId();
    }

    @GetMapping("/query")
    public List<Map<String, Object>> query(
            @RequestParam(defaultValue = "all") String algorithm,
            @RequestParam(required = false) Long recordId,
            @RequestParam(required = false) Integer cityCount,
            @RequestParam(required = false) Integer algoSeed,
            @RequestParam(required = false) Integer citySeed,
            @RequestParam(defaultValue = "") String date) {

        Long userId = getUserId();
        if (userId == null) return List.of();

        List<HistoryRecord> records = historyService.query(algorithm, recordId, cityCount, algoSeed, citySeed, date, userId);

        return records.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("algorithm_id", r.getAlgorithmId());
            m.put("algorithm_label", getLabel(r.getAlgorithmId()));
            m.put("city_count", r.getCityCount());
            m.put("input_text", r.getInputText());
            m.put("seed", r.getSeed());
            m.put("algo_seed", r.getAlgoSeed());
            m.put("city_seed", r.getCitySeed());
            m.put("params", r.getParams());
            m.put("best_distance", r.getBestDistance());
            m.put("best_path", r.getBestPath());
            m.put("best_path_text", r.getBestPathText());
            m.put("elapsed_ms", r.getElapsedMs());
            m.put("complexity_score", r.getComplexityScore());
            m.put("complexity_label", r.getComplexityLabel());
            m.put("user_id", r.getUserId());
            m.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().format(DATE_FMT) : "");
            return m;
        }).collect(Collectors.toList());
    }

    /** Check whether the selected algo_seed + city_seed combos have already been run.
     *  Only combos with algo_seed > 0 are checked (0 means unsaved/custom params, not pinned). */
    @PostMapping("/check_duplicate")
    public Map<String, Object> checkDuplicate(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");

        @SuppressWarnings("unchecked")
        List<String> algorithms = (List<String>) body.getOrDefault("algorithms", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> algoSeeds = (Map<String, Object>) body.getOrDefault("algo_seeds", Map.of());
        Object csObj = body.get("city_seed");
        Integer citySeed = csObj instanceof Number ? ((Number) csObj).intValue() : null;
        if (citySeed == null) return Map.of("ok", true, "duplicates", List.of());

        List<Map<String, Object>> duplicates = new ArrayList<>();
        for (String algoId : algorithms) {
            Object asObj = algoSeeds.get(algoId);
            int algoSeed = asObj instanceof Number ? ((Number) asObj).intValue() : 0;
            if (algoSeed <= 0) continue; // unsaved params — combo not pinned, skip
            List<Long> ids = historyService.findIdsByCombo(algoId, algoSeed, citySeed, userId);
            if (!ids.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("algorithm_id", algoId);
                d.put("algorithm_label", getLabel(algoId));
                d.put("algo_seed", algoSeed);
                d.put("city_seed", citySeed);
                d.put("count", ids.size());
                d.put("ids", ids);
                duplicates.add(d);
            }
        }
        return Map.of("ok", true, "duplicates", duplicates);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.getOrDefault("ids", List.of());
        List<Long> ids = rawIds.stream().map(Number::longValue).collect(Collectors.toList());
        return historyService.deleteRecords(ids, userId);
    }

    /** Re-match history records' algo_seed. Returns list of seeds whose params changed. */
    @PostMapping("/refresh_seeds")
    public Map<String, Object> refreshSeeds() {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        List<HistoryRecord> records = historyRepo.findAllByUserId(userId);
        int updated = 0;
        List<Map<String, Object>> changedSeeds = new ArrayList<>();

        for (HistoryRecord r : records) {
            try {
                if (r.getAlgoSeed() == 0) continue;
                // Look up current seed by seed number
                var seed = algoSeedRepo.findBySeedNumber(userId, r.getAlgorithmId(), r.getAlgoSeed());
                if (seed == null) {
                    // Seed deleted → reset to 0
                    historyRepo.updateAlgoSeed(r.getId(), 0);
                    updated++;
                    continue;
                }
                // Compare current seed params with stored params
                String storedParams = r.getParams();
                if (storedParams == null || storedParams.isBlank()) continue;
                String currentParams = seed.getParams();
                if (currentParams == null) continue;

                Map<String, Object> sp = AlgorithmService.MAPPER.readValue(storedParams, new TypeReference<Map<String, Object>>() {});
                Map<String, Object> cp = AlgorithmService.MAPPER.readValue(currentParams, new TypeReference<Map<String, Object>>() {});
                java.util.TreeMap<String, Object> normalizedStored = AlgorithmService.normalizeParams(r.getAlgorithmId(), sp);
                java.util.TreeMap<String, Object> normalizedCurrent = AlgorithmService.normalizeParams(r.getAlgorithmId(), cp);
                String storedJson = AlgorithmService.MAPPER.writeValueAsString(normalizedStored);
                String currentJson = AlgorithmService.MAPPER.writeValueAsString(normalizedCurrent);

                if (!storedJson.equals(currentJson)) {
                    // Params changed!
                    if (r.getCitiesJson() == null || r.getCitiesJson().isBlank()) {
                        // Old record without city data – can't rerun, just reset
                        historyRepo.updateAlgoSeed(r.getId(), 0);
                        updated++;
                    } else {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("history_id", r.getId());
                        info.put("algorithm_label", getLabel(r.getAlgorithmId()));
                        info.put("algo_seed", r.getAlgoSeed());
                        changedSeeds.add(info);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to refresh seed for history id={}: {}", r.getId(), e.getMessage());
            }
        }
        return Map.of("ok", true, "updated", updated, "changed_seeds", changedSeeds);
    }

    /** Re-run algorithms for given history records using current seed params. */
    @PostMapping("/rerun")
    public Map<String, Object> rerun(@RequestBody Map<String, Object> body) {
        Long userId = getUserId();
        if (userId == null) return Map.of("ok", false, "error", "请先登录");
        @SuppressWarnings("unchecked")
        List<Number> rawIds = (List<Number>) body.getOrDefault("history_ids", List.of());
        int rerunCount = 0;

        for (Number n : rawIds) {
            long historyId = n.longValue();
            try {
                // Load history record
                List<HistoryRecord> recs = historyRepo.findAllByUserId(userId);
                HistoryRecord rec = null;
                for (HistoryRecord r : recs) { if (r.getId() == historyId) { rec = r; break; } }
                if (rec == null || rec.getCitiesJson() == null) continue;

                // Get current seed params
                var seed = algoSeedRepo.findBySeedNumber(userId, rec.getAlgorithmId(), rec.getAlgoSeed());
                if (seed == null) continue;

                @SuppressWarnings("unchecked")
                Map<String, Object> newParams = AlgorithmService.MAPPER.readValue(seed.getParams(), Map.class);
                log.info("[Rerun] id={} algo={} seed={} params={}",
                    historyId, rec.getAlgorithmId(), rec.getSeed(), AlgorithmService.MAPPER.writeValueAsString(newParams));

                // Parse cities
                java.util.List<Map<String, Object>> cities = AlgorithmService.MAPPER.readValue(
                    rec.getCitiesJson(), new TypeReference<java.util.List<Map<String, Object>>>() {});

                // Parse weights from input_text
                Map<String, Object> weightsObj = new HashMap<>();
                String inputText = rec.getInputText();
                if (inputText != null) {
                    for (String line : inputText.split("\\r?\\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split(":");
                        if (parts.length == 2) {
                            try {
                                weightsObj.put(parts[0].trim(), Double.parseDouble(parts[1].trim()));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                // Run algorithm: use original algo seed (= baseSeed + index*100) for reproducibility
                Map<String, Object> result = algoService.runAlgorithm(
                    rec.getAlgorithmId(), cities, newParams, rec.getSeed(), weightsObj, userId, null);

                // Check if algorithm returned an error
                if (result.containsKey("error") || !result.containsKey("best_distance")) {
                    log.warn("[Rerun] Algorithm returned error for id={}: {}", historyId, result.get("error"));
                    continue;
                }

                // Save as new history record
                HistoryRecord newRec = new HistoryRecord();
                newRec.setAlgorithmId(rec.getAlgorithmId());
                newRec.setCityCount(rec.getCityCount());
                newRec.setInputText(rec.getInputText());
                newRec.setCitiesJson(rec.getCitiesJson());
                newRec.setSeed(rec.getSeed());
                newRec.setAlgoSeed(rec.getAlgoSeed());
                newRec.setCitySeed(rec.getCitySeed());
                newRec.setParams(AlgorithmService.MAPPER.writeValueAsString(newParams));
                Number bd = (Number) result.get("best_distance");
                newRec.setBestDistance(bd != null ? bd.doubleValue() : 0.0);
                newRec.setBestPath(AlgorithmService.MAPPER.writeValueAsString(result.get("best_path")));
                newRec.setBestPathText(String.valueOf(result.get("best_path_text")));
                Number em = (Number) result.get("elapsed_ms");
                newRec.setElapsedMs(em != null ? em.intValue() : 0);
                Number cs = (Number) result.get("complexity_score");
                newRec.setComplexityScore(cs != null ? cs.intValue() : 0);
                newRec.setComplexityLabel(String.valueOf(result.get("complexity_label")));
                newRec.setUserId(userId);
                newRec.setCreatedAt(java.time.LocalDateTime.now());
                historyRepo.insert(newRec);
                // Mark old record so it won't appear as changed again
                historyRepo.updateAlgoSeed(rec.getId(), 0);
                rerunCount++;
                log.info("[Rerun] Done id={} -> new_id={}", historyId, newRec.getId());
            } catch (Exception e) {
                log.error("Failed to rerun history id={}: {}", historyId, e.getMessage());
            }
        }
        return Map.of("ok", true, "rerun_count", rerunCount);
    }

    private String getLabel(String id) {
        return com.srtp.server.service.AlgorithmService.ALGO_LABELS.getOrDefault(id, id);
    }
}
