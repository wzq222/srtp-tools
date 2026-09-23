package com.srtp.server.repository;

import com.srtp.server.model.User;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<User> rowMapper = (rs, rowNum) -> new User(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("password_hash"),
        rs.getTimestamp("created_at").toLocalDateTime()
    );

    public Optional<User> findByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = :username";
        MapSqlParameterSource params = new MapSqlParameterSource("username", username);
        var list = jdbc.query(sql, params, rowMapper);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<User> findById(Long id) {
        String sql = "SELECT * FROM users WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        var list = jdbc.query(sql, params, rowMapper);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public long insert(User user) {
        String sql = "INSERT INTO users (username, password_hash, created_at) VALUES (:username, :passwordHash, NOW())";
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("username", user.getUsername())
            .addValue("passwordHash", user.getPasswordHash());
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(sql, params, holder, new String[]{"id"});
        Number key = holder.getKey();
        return key != null ? key.longValue() : 0;
    }

    public void updatePassword(Long userId, String newHash) {
        String sql = "UPDATE users SET password_hash = :hash WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", userId)
            .addValue("hash", newHash));
    }
}
