package com.cc.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Service
public class SessionPersistenceService {

    private final JdbcTemplate jdbcTemplate;

    public SessionPersistenceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_sessions (
                    id VARCHAR(128) PRIMARY KEY,
                    mode VARCHAR(32) NOT NULL,
                    title TEXT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    payload TEXT NOT NULL
                )
                """);
    }

    public List<String> listPayloads() {
        return jdbcTemplate.query(
                "SELECT payload FROM app_sessions ORDER BY updated_at DESC",
                (rs, rowNum) -> rs.getString("payload")
        );
    }

    public void save(JsonNode session, String payload) {
        String id = requiredText(session, "id");
        String mode = requiredText(session, "mode");
        String title = session.path("title").asText("");
        long updatedAt = session.path("updatedAt").asLong(System.currentTimeMillis());

        jdbcTemplate.update("""
                INSERT INTO app_sessions (id, mode, title, updated_at, payload)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    mode = EXCLUDED.mode,
                    title = EXCLUDED.title,
                    updated_at = EXCLUDED.updated_at,
                    payload = EXCLUDED.payload
                """, id, mode, title, updatedAt, payload);
    }

    public void delete(String sessionId) {
        jdbcTemplate.update("DELETE FROM app_sessions WHERE id = ?", sessionId);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }
}
