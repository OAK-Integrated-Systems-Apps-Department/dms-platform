package com.oak.dms.metadata.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Plain JDBC rather than a native {@code @Query}: the check needs
 * PostgreSQL's {@code ?} key-exists operator - the form the
 * default-{@code jsonb_ops} {@code ix_dm_values} GIN index serves - and
 * Hibernate's native-query parser treats any {@code ?} as a bind parameter
 * ("Mixing of ? parameters and other forms like ?1 is not supported"), even
 * as the JDBC driver's {@code ??} escape. {@code jsonb_exists()} would
 * parse, but can't use the index. Through JdbcTemplate the SQL reaches the
 * driver untouched, which turns {@code ??} back into a literal {@code ?};
 * it still joins the surrounding JPA transaction's connection.
 */
@RequiredArgsConstructor
public class DocumentMetadataRepositoryImpl implements DocumentMetadataRepositoryCustom {

    private static final String EXISTS_BY_FIELD_KEY_SQL = """
            select exists (
                select 1 from document_metadata
                where tenant_id = ? and field_values ?? ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean existsByTenantIdAndFieldKey(UUID tenantId, String fieldId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(EXISTS_BY_FIELD_KEY_SQL, Boolean.class, tenantId, fieldId));
    }
}
