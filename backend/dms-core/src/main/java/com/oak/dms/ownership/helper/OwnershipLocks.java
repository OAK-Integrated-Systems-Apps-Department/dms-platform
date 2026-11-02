package com.oak.dms.ownership.helper;

import com.oak.dms.relationship.helper.RelationshipLocks;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Row locks for ownership changes. Documents are locked in ascending id order - the order the relationship
 * code uses ({@code RelationshipKeys.compare} is the same bytewise order Postgres sorts uuids in) - so a
 * transfer and a relationship being created on the same documents queue up instead of deadlocking.
 * <p>
 * The rows come straight back from the {@code FOR UPDATE} query: no entity is loaded first, so there is no
 * stale cached copy (the pitfall noted in {@code DocumentRepository#lockByDocumentIdAndTenantId}). Unlike that
 * query this one keeps Recycle Bin documents (D7) and leaves out purged ones, which no longer exist as documents.
 */
@Component
@RequiredArgsConstructor
public class OwnershipLocks {

    private static final String LOCK_SQL = """
            select d.document_id, d.tenant_id, d.title, d.owner_user_id, d.owner_department_id, d.ownership_state,
                   d.folder_id, d.access_mode, d.is_template, d.is_deleted
              from documents d
             where d.tenant_id = :tenantId and d.document_id in (:ids) and d.purged_at is null
             order by d.document_id
               for update
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RelationshipLocks locks;

    /** The 5s lock timeout, transaction-local. Call first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyLockTimeout() {
        locks.applyLockTimeout();
    }

    /** Locks and returns the live (not purged) documents among {@code ids}, ascending by id. Missing ones are simply absent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OwnedDocument> lockDocuments(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        try {
            return query(tenantId, ids);
        } catch (UncategorizedSQLException e) {
            // JdbcTemplate doesn't translate Postgres' lock_not_available (55P03) / deadlock (40P01) the way JPA does;
            // the global handler turns this into the same retryable 409 CONCURRENT_UPDATE as everywhere else.
            String state = e.getSQLException() == null ? null : e.getSQLException().getSQLState();
            if ("55P03".equals(state) || "40P01".equals(state)) {
                throw new CannotAcquireLockException("Documents are being changed by someone else", e);
            }
            throw e;
        }
    }

    /** The same rows without locking - for previews. Purged documents are absent. */
    public List<OwnedDocument> readDocuments(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return query(LOCK_SQL.replace("for update", ""), tenantId, ids);
    }

    private List<OwnedDocument> query(UUID tenantId, Collection<UUID> ids) {
        return query(LOCK_SQL, tenantId, ids);
    }

    private List<OwnedDocument> query(String sql, UUID tenantId, Collection<UUID> ids) {
        return jdbc.query(sql, new MapSqlParameterSource("tenantId", tenantId).addValue("ids", ids),
                (rs, row) -> new OwnedDocument(rs.getObject("document_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("title"), rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("owner_department_id", UUID.class), rs.getString("ownership_state"),
                        rs.getObject("folder_id", UUID.class), rs.getString("access_mode"),
                        rs.getBoolean("is_template"), rs.getBoolean("is_deleted")));
    }
}
