package com.srtp.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.srtp.server.repository.AlgoParamSeedRepository;
import com.srtp.server.model.AlgoParamSeed;
import com.srtp.server.util.PythonExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.*;

@Service
public class AlgorithmService {

    private static final Logger log = LoggerFactory.getLogger(AlgorithmService.class);
    public static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${python.executable:python}")
    private String pythonExe;

    @Value("${python.script-dir:python}")
    private String scriptDir;

    private String absoluteScriptDir;

    private final AlgoParamSeedRepository algoSeedRepo;

    public AlgorithmService(AlgoParamSeedRepository algoSeedRepo) {
        this.algoSeedRepo = algoSeedRepo;
    }

    @PostConstruct
    public void init() {
        // Resolve script directory to absolute path
        File dir = new File(scriptDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), scriptDir);
        }
        absoluteScriptDir = dir.getAbsolutePath();
        log.info("Python script dir: {}", absoluteScriptDir);
    }

    @PostConstruct
    public void migrateOldSeeds() {
        List<AlgoParamSeed> allSeeds = algoSeedRepo.findAll();
        int updatedCount = 0;
        for (AlgoParamSeed seed : allSeeds) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> oldParams = MAPPER.readValue(seed.getParams(), Map.class);
                TreeMap<String, Object> normalized = normalizeParams(seed.getAlgorithmId(), oldParams);
                String newParamsJson = MAPPER.writeValueAsString(normalized);
                if (!newParamsJson.equals(seed.getParams())) {
                    algoSeedRepo.updateParams(seed.getId(), newParamsJson);
                    updatedCount++;
                }
            } catch (Exception e) {
                log.warn("Failed to migrate seed id={}: {}", seed.getId(), e.getMessage());
            }
        }
        if (updatedCount > 0) {
            log.info("migrateOldSeeds: updated {} seeds", updatedCount);
        }
    }

    public static final List<String> ALL_ALGORITHMS = List.of("sa", "ts", "ga", "aca", "pso", "som", "dp", "bnb");

    public static final Map<String, String> ALGO_LABELS = Map.of(
        "sa",  "SA 模拟退火",
        "ts",  "TS 禁忌搜索",
        "ga",  "GA 遗传算法",
        "aca", "ACA 蚁群算法",
        "pso", "PSO 粒子群",
        "som", "SOM 自组织映射",
        "dp",  "DP 动态规划",
        "bnb", "BnB 分支限界"
    );

    // ============ Param Specs ============
    public static final Map<String, List<Map<String, String>>> PARAM_SPECS = Map.of(
        "pso", List.of(
            Map.of("key","iterations","label","迭代次数","type","int"),
            Map.of("key","particle_count","label","粒子数","type","int"),
            Map.of("key","inertia_weight","label","惯性权重","type","float"),
            Map.of("key","cognitive_weight","label","认知权重","type","float"),
            Map.of("key","social_weight","label","社会权重","type","float")
        ),
        "sa", List.of(
            Map.of("key","initial_temp","label","初始温度","type","float"),
            Map.of("key","final_temp","label","终止温度","type","float"),
            Map.of("key","cooling_rate","label","冷却因子","type","float"),
            Map.of("key","inner_iterations","label","内循环次数","type","int")
        ),
        "ga", List.of(
            Map.of("key","population_size","label","种群大小","type","int"),
            Map.of("key","generations","label","迭代代数","type","int"),
            Map.of("key","crossover_rate","label","交叉概率","type","float"),
            Map.of("key","mutation_rate","label","变异概率","type","float"),
            Map.of("key","elite_count","label","精英保留数","type","int")
        ),
        "aca", List.of(
            Map.of("key","ant_count","label","蚂蚁数量","type","int"),
            Map.of("key","iterations","label","迭代次数","type","int"),
            Map.of("key","alpha","label","信息素因子 α","type","float"),
            Map.of("key","beta","label","启发因子 β","type","float"),
            Map.of("key","evaporation","label","挥发率 ρ","type","float"),
            Map.of("key","q","label","信息素强度 Q","type","float")
        ),
        "som", List.of(
            Map.of("key","iterations","label","迭代次数","type","int"),
            Map.of("key","learning_rate","label","学习率","type","float")
        ),
        "ts", List.of(
            Map.of("key","iterations","label","迭代次数","type","int"),
            Map.of("key","taboo_size","label","禁忌表长度","type","int"),
            Map.of("key","neighbor_count","label","邻域采样数","type","int")
        ),
        "bnb", List.of(),
        "dp", List.of()
    );

    // ============ Auto params (city-count-based defaults) ============
    private static String algoCode(String id) {
        return switch (id) {
            case "sa" -> "SA"; case "ts" -> "TS"; case "ga" -> "GA"; case "aca" -> "ACA";
            case "pso" -> "PSO"; case "som" -> "SOM"; case "dp" -> "DP"; case "bnb" -> "BNB";
            default -> id.toUpperCase();
        };
    }

    public static Map<String, Map<String, Object>> autoParamsForCityCount(int cityCount) {
        if (cityCount <= 10) {
            return Map.of(
                "PSO", Map.<String,Object>of("iterations",1500,"particle_count",80,"inertia_weight",0.7,"cognitive_weight",1.4,"social_weight",1.8),
                "SA", Map.<String,Object>of("initial_temp",1000.0,"final_temp",0.1,"cooling_rate",0.95,"inner_iterations",200),
                "GA", Map.<String,Object>of("population_size",80,"generations",300,"crossover_rate",0.9,"mutation_rate",0.2,"elite_count",2),
                "ACA", Map.<String,Object>of("ant_count",20,"iterations",120,"alpha",1.0,"beta",4.0,"evaporation",0.3,"q",100.0),
                "SOM", Map.<String,Object>of("iterations",8000,"learning_rate",0.8),
                "TS", Map.<String,Object>of("iterations",500,"taboo_size",5,"neighbor_count",400)
            );
        } else if (cityCount <= 15) {
            return Map.of(
                "PSO", Map.<String,Object>of("iterations",1800,"particle_count",90,"inertia_weight",0.7,"cognitive_weight",1.4,"social_weight",1.8),
                "SA", Map.<String,Object>of("initial_temp",1000.0,"final_temp",0.1,"cooling_rate",0.95,"inner_iterations",40),
                "GA", Map.<String,Object>of("population_size",40,"generations",120,"crossover_rate",0.9,"mutation_rate",0.2,"elite_count",2),
                "ACA", Map.<String,Object>of("ant_count",80,"iterations",600,"alpha",1.0,"beta",4.0,"evaporation",0.3,"q",100.0),
                "SOM", Map.<String,Object>of("iterations",10000,"learning_rate",0.8),
                "TS", Map.<String,Object>of("iterations",800,"taboo_size",7,"neighbor_count",400)
            );
        } else {
            return Map.of(
                "PSO", Map.<String,Object>of("iterations",2200,"particle_count",120,"inertia_weight",0.7,"cognitive_weight",1.4,"social_weight",1.8),
                "SA", Map.<String,Object>of("initial_temp",1000.0,"final_temp",0.1,"cooling_rate",0.95,"inner_iterations",40),
                "GA", Map.<String,Object>of("population_size",80,"generations",300,"crossover_rate",0.9,"mutation_rate",0.2,"elite_count",2),
                "ACA", Map.<String,Object>of("ant_count",40,"iterations",300,"alpha",1.0,"beta",4.0,"evaporation",0.3,"q",100.0),
                "SOM", Map.<String,Object>of("iterations",12000,"learning_rate",0.8),
                "TS", Map.<String,Object>of("iterations",1000,"taboo_size",10,"neighbor_count",400)
            );
        }
    }

    /**
     * Normalize parameter values to consistent types so they serialize identically
     * regardless of whether they come from buildParams() or from JSON deserialization.
     * Int fields → Integer, Float fields → Double, custom algo fields → Double.
     */
    public static TreeMap<String, Object> normalizeParams(String algoId, Map<String, Object> params) {
        TreeMap<String, Object> result = new TreeMap<>();
        if (params == null) return result;
        List<Map<String, String>> specs = PARAM_SPECS.get(algoId);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val instanceof Number n) {
                if (specs != null) {
                    String type = "float";
                    for (Map<String, String> s : specs) {
                        if (key.equals(s.get("key"))) { type = s.get("type"); break; }
                    }
                    Object normalized = "int".equals(type) ? (Object) n.intValue() : n.doubleValue();
                    result.put(key, normalized);
                } else {
                    result.put(key, n.doubleValue());
                }
            } else {
                result.put(key, val);
            }
        }
        return result;
    }

    /** Merge user params with auto defaults. User values take priority. */
    public static Map<String, Object> buildParams(String algoId, int cityCount, Map<String, Object> userParams) {
        Map<String, Map<String, Object>> auto = autoParamsForCityCount(cityCount);
        String code = algoCode(algoId);
        Map<String, Object> merged = new LinkedHashMap<>();
        // Put auto defaults
        Map<String, Object> defaults = auto.getOrDefault(code, Map.of());
        if (defaults != null) merged.putAll(defaults);
        // Override with user values
        if (userParams != null) {
            List<Map<String, String>> specs = PARAM_SPECS.get(algoId);
            if (specs == null) {
                // Custom algorithm: pass user params as-is (frontend converts types)
                merged.putAll(userParams);
            } else {
                for (Map<String, String> spec : specs) {
                    String key = spec.get("key");
                    if (userParams.containsKey(key)) {
                        Object val = userParams.get(key);
                        String type = spec.get("type");
                        if (val instanceof Number n) {
                            merged.put(key, "int".equals(type) ? n.intValue() : n.doubleValue());
                        } else {
                            // Try to parse from string
                            try {
                                if ("int".equals(type)) merged.put(key, Integer.parseInt(val.toString()));
                                else merged.put(key, Double.parseDouble(val.toString()));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        return merged;
    }

    /** Return param definitions with defaults for the frontend */
    public static Map<String, Object> getParamDefs(String algoId, int cityCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", algoId);
        result.put("label", ALGO_LABELS.getOrDefault(algoId, algoId));
        List<Map<String, String>> specs = PARAM_SPECS.getOrDefault(algoId, List.of());
        Map<String, Object> autoDefaults = buildParams(algoId, cityCount, null);
        List<Map<String, Object>> params = new ArrayList<>();
        for (Map<String, String> spec : specs) {
            Map<String, Object> p = new LinkedHashMap<>(spec);
            String key = spec.get("key");
            if (autoDefaults.containsKey(key)) p.put("default", autoDefaults.get(key));
            params.add(p);
        }
        result.put("params", params);
        result.put("defaults", autoDefaults);
        return result;
    }

    public Map<String, Object> runAlgorithm(String algoId, List<Map<String, Object>> cities,
                                             Map<String, Object> params, Integer seed,
                                             Map<String, Object> weightsObj, Long userId,
                                             Map<String, String> customInfo) {
        try {
            ObjectNode input = MAPPER.createObjectNode();
            input.put("algorithm_id", algoId);

            ArrayNode citiesNode = input.putArray("cities");
            for (Map<String, Object> c : cities) {
                ObjectNode cn = citiesNode.addObject();
                cn.put("name", String.valueOf(c.getOrDefault("name", "")));
                cn.put("x", ((Number) c.getOrDefault("x", 0)).doubleValue());
                cn.put("y", ((Number) c.getOrDefault("y", 0)).doubleValue());
            }

            if (params != null && !params.isEmpty()) {
                ObjectNode pn = input.putObject("params");
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Number) pn.put(e.getKey(), ((Number) v).doubleValue());
                    else pn.put(e.getKey(), String.valueOf(v));
                }
            }

            if (seed != null) {
                input.put("seed", seed);
            }

            if (weightsObj != null && !weightsObj.isEmpty()) {
                ObjectNode wn = input.putObject("weights");
                for (Map.Entry<String, Object> e : weightsObj.entrySet()) {
                    Object v = e.getValue();
                    wn.put(e.getKey(), v instanceof Number ? ((Number) v).doubleValue() : Double.parseDouble(v.toString()));
                }
            }

            // Custom algorithm: pass code + iter/pop keys + label to Python bridge
            if (customInfo != null) {
                String code = customInfo.get("code");
                if (code != null && !code.isEmpty()) {
                    input.put("custom_code", code);
                    String ik = customInfo.get("iter_key");
                    String pk = customInfo.get("pop_key");
                    String lb = customInfo.get("label");
                    if (ik != null && !ik.isEmpty()) input.put("custom_iter_key", ik);
                    if (pk != null && !pk.isEmpty()) input.put("custom_pop_key", pk);
                    if (lb != null && !lb.isEmpty()) input.put("algorithm_label", lb);
                }
            }

            String inputJson = MAPPER.writeValueAsString(input);
            log.info("[Algorithm] {} params={}", algoId, params != null ? MAPPER.writeValueAsString(params) : "null");
            log.debug("Python input: {}", inputJson);

            JsonNode result = PythonExecutor.execute(pythonExe, absoluteScriptDir, inputJson);

            Map<String, Object> map = new HashMap<>();
            // Check for error from Python bridge
            if (result.has("error")) {
                map.put("algorithm_id", algoId);
                map.put("algorithm_label", ALGO_LABELS.getOrDefault(algoId, algoId));
                map.put("error", result.path("error").asText());
                map.put("best_distance", 0.0);
                map.put("elapsed_ms", 0);
                map.put("best_path", List.of());
                map.put("best_path_text", "");
                return map;
            }

            map.put("algorithm_id", result.path("algorithm_id").asText());
            map.put("algorithm_label", result.path("algorithm_label").asText());
            map.put("best_distance", result.path("best_distance").asDouble());
            map.put("elapsed_ms", result.path("elapsed_ms").asInt());

            // Parse best_path array
            List<Integer> bestPath = new ArrayList<>();
            JsonNode pathNode = result.path("best_path");
            if (pathNode.isArray()) {
                for (JsonNode n : pathNode) bestPath.add(n.asInt());
            }
            map.put("best_path", bestPath);
            map.put("best_path_text", result.path("best_path_text").asText());

            // Complexity analysis
            map.put("complexity_score", result.path("complexity_score").asInt());
            map.put("complexity_label", result.path("complexity_label").asText());

            return map;
        } catch (Exception e) {
            log.error("Algorithm {} failed", algoId, e);
            Map<String, Object> err = new HashMap<>();
            err.put("algorithm_id", algoId);
            err.put("algorithm_label", ALGO_LABELS.getOrDefault(algoId, algoId));
            err.put("error", e.getMessage());
            return err;
        }
    }
}
