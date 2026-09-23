package com.srtp.server.repository;

import com.srtp.server.model.AlgoParamSeed;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class AlgoParamSeedRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AlgoParamSeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<AlgoParamSeed> rowMapper = (rs, rowNum) -> {
        AlgoParamSeed s = new AlgoParamSeed();
        s.setId(rs.getLong("id"));
        s.setUserId(rs.getLong("user_id"));
        s.setAlgorithmId(rs.getString("algorithm_id"));
        s.setSeedNumber(rs.getInt("seed_number"));
        s.setParams(rs.getString("params"));
        s.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return s;
    };

    public List<AlgoParamSeed> findAll() {
        String sql = "SELECT * FROM algo_param_seed ORDER BY id";
        return jdbc.query(sql, new MapSqlParameterSource(), rowMapper);
    }

    public List<AlgoParamSeed> findAllByUserIdAndAlgorithm(Long userId, String algorithmId) {
        String sql = "SELECT * FROM algo_param_seed WHERE user_id = :userId AND algorithm_id = :algoId ORDER BY seed_number";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("algoId", algorithmId);
        return jdbc.query(sql, params, rowMapper);
    }

    public AlgoParamSeed findBySeedNumber(Long userId, String algorithmId, int seedNumber) {
        String sql = "SELECT * FROM algo_param_seed WHERE user_id = :userId AND algorithm_id = :algoId AND seed_number = :seed";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("algoId", algorithmId).addValue("seed", seedNumber);
        List<AlgoParamSeed> list = jdbc.query(sql, params, rowMapper);
        return list.isEmpty() ? null : list.get(0);
    }

    public AlgoParamSeed findByParams(Long userId, String algorithmId, String params) {
        String sql = "SELECT * FROM algo_param_seed WHERE user_id = :userId AND algorithm_id = :algoId AND params = :params";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("algoId", algorithmId).addValue("params", params);
        List<AlgoParamSeed> list = jdbc.query(sql, p, rowMapper);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(AlgoParamSeed seed) {
        String sql = "INSERT INTO algo_param_seed (user_id, algorithm_id, seed_number, params, created_at) " +
                "VALUES (:userId, :algorithmId, :seedNumber, :params, :createdAt)";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", seed.getUserId())
                .addValue("algorithmId", seed.getAlgorithmId())
                .addValue("seedNumber", seed.getSeedNumber())
                .addValue("params", seed.getParams())
                .addValue("createdAt", seed.getCreatedAt() != null ? seed.getCreatedAt() : LocalDateTime.now());
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(sql, p, holder, new String[]{"id"});
        Number key = holder.getKey();
        return key != null ? key.longValue() : 0;
    }

    public boolean updateParams(Long id, String params) {
        String sql = "UPDATE algo_param_seed SET params = :params WHERE id = :id";
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("params", params);
        return jdbc.update(sql, p) > 0;
    }

    public boolean delete(Long id, Long userId) {
        String sql = "DELETE FROM algo_param_seed WHERE id = :id AND user_id = :userId";
        return jdbc.update(sql, new MapSqlParameterSource().addValue("id", id).addValue("userId", userId)) > 0;
    }

    public int generateUniqueSeedNumber(Long userId, String algorithmId) {
        // Generate a random 1-5 digit number (1 - 99999) that doesn't exist for this user+algo
        int maxAttempts = 100;
        for (int i = 0; i < maxAttempts; i++) {
            int candidate = 1 + (int) (Math.random() * 99999);
            String sql = "SELECT COUNT(*) FROM algo_param_seed WHERE user_id = :uid AND algorithm_id = :aid AND seed_number = :sn";
            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("uid", userId).addValue("aid", algorithmId).addValue("sn", candidate);
            Integer count = jdbc.queryForObject(sql, p, Integer.class);
            if (count != null && count == 0) return candidate;
        }
        // Fallback: use max+1
        String sql = "SELECT COALESCE(MAX(seed_number), 0) + 1 FROM algo_param_seed WHERE user_id = :uid AND algorithm_id = :aid";
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("uid", userId).addValue("aid", algorithmId);
        Integer next = jdbc.queryForObject(sql, p, Integer.class);
        return next != null ? next : 1;
    }
}
