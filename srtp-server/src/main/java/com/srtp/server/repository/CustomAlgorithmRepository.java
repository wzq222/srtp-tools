package com.srtp.server.repository;

import com.srtp.server.model.CustomAlgorithm;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class CustomAlgorithmRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CustomAlgorithmRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private final RowMapper<CustomAlgorithm> rowMapper = (rs, rowNum) -> {
        CustomAlgorithm a = new CustomAlgorithm();
        a.setId(rs.getLong("id"));
        a.setUserId(rs.getLong("user_id"));
        a.setAlgoId(rs.getString("algo_id"));
        a.setLabel(rs.getString("label"));
        a.setCode(rs.getString("code"));
        a.setParamSpecs(rs.getString("param_specs"));
        a.setIterKey(rs.getString("iter_key"));
        a.setPopKey(rs.getString("pop_key"));
        a.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        a.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return a;
    };

    public List<CustomAlgorithm> findAllByUserId(Long userId) {
        return jdbc.query("SELECT * FROM custom_algorithm WHERE user_id = :userId ORDER BY id",
                new MapSqlParameterSource("userId", userId), rowMapper);
    }

    public CustomAlgorithm findByUserAndAlgoId(Long userId, String algoId) {
        List<CustomAlgorithm> list = jdbc.query(
                "SELECT * FROM custom_algorithm WHERE user_id = :userId AND algo_id = :algoId",
                new MapSqlParameterSource("userId", userId).addValue("algoId", algoId), rowMapper);
        return list.isEmpty() ? null : list.get(0);
    }

    public void upsert(Long userId, String algoId, String label, String code, String paramSpecs,
                       String iterKey, String popKey) {
        LocalDateTime now = LocalDateTime.now();
        String sql = "INSERT INTO custom_algorithm (user_id, algo_id, label, code, param_specs, " +
                "iter_key, pop_key, created_at, updated_at) VALUES " +
                "(:userId, :algoId, :label, :code, :paramSpecs, :iterKey, :popKey, :now1, :now2) " +
                "ON DUPLICATE KEY UPDATE label = :label2, code = :code2, param_specs = :paramSpecs2, " +
                "iter_key = :iterKey2, pop_key = :popKey2, updated_at = :now3";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("algoId", algoId)
                .addValue("label", label).addValue("code", code).addValue("paramSpecs", paramSpecs)
                .addValue("iterKey", iterKey).addValue("popKey", popKey)
                .addValue("now1", now).addValue("now2", now)
                .addValue("label2", label).addValue("code2", code).addValue("paramSpecs2", paramSpecs)
                .addValue("iterKey2", iterKey).addValue("popKey2", popKey).addValue("now3", now);
        jdbc.update(sql, params);
    }

    public int delete(Long userId, String algoId) {
        return jdbc.update("DELETE FROM custom_algorithm WHERE user_id = :userId AND algo_id = :algoId",
                new MapSqlParameterSource("userId", userId).addValue("algoId", algoId));
    }
}
