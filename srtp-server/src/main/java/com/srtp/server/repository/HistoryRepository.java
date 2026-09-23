package com.srtp.server.repository;

import com.srtp.server.model.HistoryRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class HistoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public HistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<HistoryRecord> rowMapper = (rs, rowNum) -> {
        HistoryRecord r = new HistoryRecord();
        r.setId(rs.getLong("id"));
        r.setAlgorithmId(rs.getString("algorithm_id"));
        r.setCityCount(rs.getInt("city_count"));
        r.setInputText(rs.getString("input_text"));
        r.setCitiesJson(rs.getString("cities_json"));
        r.setSeed(rs.getInt("seed"));
        r.setAlgoSeed(rs.getInt("algo_seed"));
        r.setCitySeed(rs.getInt("city_seed"));
        r.setParams(rs.getString("params"));
        r.setBestDistance(rs.getDouble("best_distance"));
        r.setBestPath(rs.getString("best_path"));
        r.setBestPathText(rs.getString("best_path_text"));
        r.setElapsedMs(rs.getInt("elapsed_ms"));
        r.setComplexityScore(rs.getLong("complexity_score"));
        r.setComplexityLabel(rs.getString("complexity_label"));
        long uid = rs.getLong("user_id");
        r.setUserId(rs.wasNull() ? null : uid);
        r.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return r;
    };

    public long insert(HistoryRecord record) {
        String sql = """
            INSERT INTO history (algorithm_id, city_count, input_text, cities_json, seed, algo_seed, city_seed, params,
                best_distance, best_path, best_path_text, elapsed_ms, complexity_score, complexity_label,
                user_id, created_at)
            VALUES (:algorithmId, :cityCount, :inputText, :citiesJson, :seed, :algoSeed, :citySeed, :params,
                :bestDistance, :bestPath, :bestPathText, :elapsedMs, :complexityScore, :complexityLabel,
                :userId, :createdAt)
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("algorithmId", record.getAlgorithmId())
            .addValue("cityCount", record.getCityCount())
            .addValue("inputText", record.getInputText())
            .addValue("citiesJson", record.getCitiesJson())
            .addValue("seed", record.getSeed())
            .addValue("algoSeed", record.getAlgoSeed())
            .addValue("citySeed", record.getCitySeed())
            .addValue("params", record.getParams())
            .addValue("bestDistance", record.getBestDistance())
            .addValue("bestPath", record.getBestPath())
            .addValue("bestPathText", record.getBestPathText())
            .addValue("elapsedMs", record.getElapsedMs())
            .addValue("complexityScore", record.getComplexityScore())
            .addValue("complexityLabel", record.getComplexityLabel())
            .addValue("userId", record.getUserId())
            .addValue("createdAt", record.getCreatedAt() != null ? record.getCreatedAt() : LocalDateTime.now());
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(sql, params, holder, new String[]{"id"});
        Number key = holder.getKey();
        return key != null ? key.longValue() : 0;
    }

    public List<HistoryRecord> findByFilter(String algorithmId, Long recordId, Integer cityCount,
                                             Integer algoSeed, Integer citySeed, String date, Long userId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM history WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (userId != null) {
            sql.append(" AND user_id = :userId");
            params.addValue("userId", userId);
        }
        if (algorithmId != null && !algorithmId.isEmpty() && !"all".equals(algorithmId)) {
            sql.append(" AND algorithm_id = :algorithmId");
            params.addValue("algorithmId", algorithmId);
        }
        if (recordId != null) {
            sql.append(" AND id = :recordId");
            params.addValue("recordId", recordId);
        }
        if (cityCount != null) {
            sql.append(" AND city_count = :cityCount");
            params.addValue("cityCount", cityCount);
        }
        if (algoSeed != null) {
            sql.append(" AND algo_seed = :algoSeed");
            params.addValue("algoSeed", algoSeed);
        }
        if (citySeed != null) {
            sql.append(" AND city_seed = :citySeed");
            params.addValue("citySeed", citySeed);
        }
        if (date != null && !date.isEmpty()) {
            // Accept both yyyy-MM-dd and MM/dd/yyyy formats
            String normalized = date;
            if (date.contains("/")) {
                String[] parts = date.split("/");
                if (parts.length == 3) normalized = parts[2] + "-" + parts[0] + "-" + parts[1];
            }
            sql.append(" AND DATE(created_at) = :date");
            params.addValue("date", normalized);
        }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.query(sql.toString(), params, rowMapper);
    }

    /** Find existing record IDs for an algorithm_id + algo_seed + city_seed combination. */
    public List<Long> findIdsByCombo(String algorithmId, Integer algoSeed, Integer citySeed, Long userId) {
        String sql = "SELECT id FROM history WHERE user_id = :userId AND algorithm_id = :algorithmId " +
                "AND algo_seed = :algoSeed AND city_seed = :citySeed ORDER BY id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("algorithmId", algorithmId)
                .addValue("algoSeed", algoSeed)
                .addValue("citySeed", citySeed);
        return jdbc.queryForList(sql, params, Long.class);
    }

    public boolean deleteByIds(List<Long> ids, Long userId) {
        if (ids == null || ids.isEmpty()) return false;
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("ids", ids);
        params.addValue("userId", userId);
        String sql = "DELETE FROM history WHERE id IN (:ids) AND user_id = :userId";
        int rows = jdbc.update(sql, params);
        return rows > 0;
    }

    public List<HistoryRecord> findAllByUserId(Long userId) {
        String sql = "SELECT * FROM history WHERE user_id = :userId ORDER BY id DESC";
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), rowMapper);
    }

    public boolean updateAlgoSeed(Long id, int algoSeed) {
        String sql = "UPDATE history SET algo_seed = :algoSeed WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id).addValue("algoSeed", algoSeed);
        return jdbc.update(sql, params) > 0;
    }
}
