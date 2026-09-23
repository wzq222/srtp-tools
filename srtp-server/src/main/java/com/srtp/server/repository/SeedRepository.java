package com.srtp.server.repository;

import com.srtp.server.model.SeedData;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SeedRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SeedRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private final RowMapper<SeedData> rowMapper = (rs, rowNum) -> {
        SeedData s = new SeedData();
        s.setId(rs.getLong("id"));
        s.setUserId(rs.getLong("user_id"));
        s.setSeed(rs.getInt("seed"));
        s.setCityCount(rs.getInt("city_count"));
        s.setCityData(rs.getString("city_data"));
        s.setWeights(rs.getString("weights"));
        s.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return s;
    };

    public List<SeedData> findAllByUserId(Long userId) {
        return jdbc.query("SELECT * FROM seed_data WHERE user_id = :userId ORDER BY seed",
                new MapSqlParameterSource("userId", userId), rowMapper);
    }

    public SeedData findByUserAndSeed(Long userId, int seed) {
        List<SeedData> list = jdbc.query(
                "SELECT * FROM seed_data WHERE user_id = :userId AND seed = :seed",
                new MapSqlParameterSource("userId", userId).addValue("seed", seed), rowMapper);
        return list.isEmpty() ? null : list.get(0);
    }

    public void upsert(Long userId, int seed, int cityCount, String cityData, String weights) {
        jdbc.update("INSERT INTO seed_data (user_id, seed, city_count, city_data, weights) " +
                "VALUES (:userId, :seed, :cityCount, :cityData, :weights) " +
                "ON DUPLICATE KEY UPDATE city_count = :cityCount2, city_data = :cityData2, weights = :weights2",
                new MapSqlParameterSource()
                        .addValue("userId", userId).addValue("seed", seed)
                        .addValue("cityCount", cityCount).addValue("cityData", cityData).addValue("weights", weights)
                        .addValue("cityCount2", cityCount).addValue("cityData2", cityData).addValue("weights2", weights));
    }

    public int delete(Long userId, int seed) {
        return jdbc.update("DELETE FROM seed_data WHERE user_id = :userId AND seed = :seed",
                new MapSqlParameterSource("userId", userId).addValue("seed", seed));
    }
}
