package com.oak.dms.search.preset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** saved_searches and saved_search_settings. Every query is scoped by tenant AND user: a preset is private. */
@Repository
public class SavedSearchRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Row(UUID presetId, String name, Map<String, Object> criteria, int schemaVersion, OffsetDateTime createdAt,
                      OffsetDateTime updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public SavedSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "preset_id, name, criteria::text, schema_version, created_at, updated_at";

    private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        try {
            return new Row(rs.getObject(1, UUID.class), rs.getString(2),
                    JSON.readValue(rs.getString(3), new TypeReference<Map<String, Object>>() {
                    }), rs.getInt(4), rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class));
        } catch (JsonProcessingException e) {
            throw new java.sql.SQLException("Unreadable preset criteria", e);
        }
    }

    /** Serialises the per-person critical section (count then insert) so two saves cannot both pass at limit minus one. */
    public void lockUser(UUID tenantId, UUID userId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class,
                "saved_search:" + tenantId + ":" + userId);
    }

    public List<Row> list(UUID tenantId, UUID userId) {
        return jdbc.query("select " + COLUMNS + " from saved_searches where tenant_id = ? and user_id = ? "
                + "order by lower(name), preset_id", SavedSearchRepository::map, tenantId, userId);
    }

    public long count(UUID tenantId, UUID userId) {
        return jdbc.queryForObject("select count(*) from saved_searches where tenant_id = ? and user_id = ?", Long.class,
                tenantId, userId);
    }

    public Optional<Row> find(UUID tenantId, UUID userId, UUID presetId) {
        return jdbc.query("select " + COLUMNS + " from saved_searches where tenant_id = ? and user_id = ? and preset_id = ?",
                SavedSearchRepository::map, tenantId, userId, presetId).stream().findFirst();
    }

    public Optional<Row> findByName(UUID tenantId, UUID userId, String name) {
        return jdbc.query("select " + COLUMNS + " from saved_searches where tenant_id = ? and user_id = ? "
                        + "and lower(btrim(name)) = lower(btrim(?))", SavedSearchRepository::map, tenantId, userId, name)
                .stream().findFirst();
    }

    public Row insert(UUID tenantId, UUID userId, String name, Map<String, Object> criteria) {
        return jdbc.queryForObject("insert into saved_searches (tenant_id, user_id, name, criteria, schema_version) "
                        + "values (?, ?, ?, ?::jsonb, ?) returning " + COLUMNS, SavedSearchRepository::map, tenantId, userId,
                name, json(criteria), PresetCriteria.CURRENT_VERSION);
    }

    /** Null name or criteria keeps the stored one. Always writes the current schema version for the criteria it writes. */
    public Optional<Row> update(UUID tenantId, UUID userId, UUID presetId, String name, Map<String, Object> criteria) {
        String json = criteria == null ? null : json(criteria);
        return jdbc.query("update saved_searches set name = coalesce(?, name), "
                        + "criteria = coalesce(?::jsonb, criteria), "
                        + "schema_version = case when ?::jsonb is null then schema_version else ? end, updated_at = now() "
                        + "where tenant_id = ? and user_id = ? and preset_id = ? returning " + COLUMNS,
                SavedSearchRepository::map, name, json, json, PresetCriteria.CURRENT_VERSION, tenantId, userId, presetId)
                .stream().findFirst();
    }

    public boolean delete(UUID tenantId, UUID userId, UUID presetId) {
        return jdbc.update("delete from saved_searches where tenant_id = ? and user_id = ? and preset_id = ?",
                tenantId, userId, presetId) > 0;
    }

    public Optional<Integer> tenantLimit(UUID tenantId) {
        return jdbc.queryForList("select max_per_user from saved_search_settings where tenant_id = ?", Integer.class, tenantId)
                .stream().findFirst();
    }

    public void setTenantLimit(UUID tenantId, int max) {
        jdbc.update("insert into saved_search_settings (tenant_id, max_per_user, updated_at) values (?, ?, now()) "
                + "on conflict (tenant_id) do update set max_per_user = excluded.max_per_user, updated_at = now()", tenantId, max);
    }

    private static String json(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
