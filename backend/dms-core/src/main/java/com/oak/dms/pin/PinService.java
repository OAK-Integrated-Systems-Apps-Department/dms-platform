package com.oak.dms.pin;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A user's own pinned documents (4.3 Pin Important Documents).
 * <p>
 * Pins are personal (PID-FR-006) and never grant anything (PID-NFR-003): every read and every pin goes
 * through {@link DocumentService#visibleItems} - the document browser's own access rules - so a pinned
 * document is exactly as visible as it would be anywhere else. A pin whose document the user can no
 * longer see (access revoked, PID-FR-008) or that has been deleted (PID-FR-009) is removed the next time
 * the pinned section is read. A document that is merely archived stays, flagged.
 */
@Service
@RequiredArgsConstructor
public class PinService {

    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentService documentService;
    private final AuditService auditService;

    @Value("${dms.pins.default-max:20}")
    private int defaultMax;

    /** One pinned document: the same row the document browser shows, plus when it was pinned. */
    public record PinnedDocument(DocumentListItem document, OffsetDateTime pinnedAt, boolean archived) {
    }

    /** The caller's pins, newest first, and how many they may have. */
    public record Pins(int limit, List<PinnedDocument> pins) {
    }

    @Transactional
    public Pins list() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId).addValue("user", userId);

        Map<UUID, OffsetDateTime> pinnedAt = new LinkedHashMap<>();
        jdbc.query("select document_id, pinned_at from document_pins where tenant_id = :tenant and user_id = :user "
                + "order by pinned_at desc", params,
                rs -> {
                    pinnedAt.put(rs.getObject("document_id", UUID.class), rs.getObject("pinned_at", OffsetDateTime.class));
                });

        List<DocumentListItem> visible = documentService.visibleItems(pinnedAt.keySet());
        Set<UUID> visibleIds = new HashSet<>();
        visible.forEach(d -> visibleIds.add(d.documentId()));

        // PID-FR-008/009: what the caller can't see any more - revoked, deleted - is dropped, not just hidden.
        List<UUID> gone = pinnedAt.keySet().stream().filter(id -> !visibleIds.contains(id)).toList();
        if (!gone.isEmpty()) {
            jdbc.update("delete from document_pins where tenant_id = :tenant and user_id = :user and document_id in (:ids)",
                    new MapSqlParameterSource(params.getValues()).addValue("ids", gone));
        }

        Set<UUID> archived = new HashSet<>();
        if (!visible.isEmpty()) {
            jdbc.query("select document_id from documents where tenant_id = :tenant and status = 'archived' "
                    + "and document_id in (:ids)",
                    new MapSqlParameterSource(params.getValues()).addValue("ids", visibleIds),
                    rs -> {
                        archived.add(rs.getObject("document_id", UUID.class));
                    });
        }

        Map<UUID, DocumentListItem> byId = new HashMap<>();
        visible.forEach(d -> byId.put(d.documentId(), d));
        List<PinnedDocument> pins = new ArrayList<>();
        for (Map.Entry<UUID, OffsetDateTime> pin : pinnedAt.entrySet()) {
            DocumentListItem item = byId.get(pin.getKey());
            if (item != null) {
                pins.add(new PinnedDocument(item, pin.getValue(), archived.contains(item.documentId())));
            }
        }
        return new Pins(limit(tenantId), pins);
    }

    /**
     * Pins a document the caller can see. Pinning one already pinned is not an error. At the limit it
     * is refused with PIN_LIMIT_REACHED (PID-FR-007), which the screen shows as it is.
     */
    @Transactional
    public void pin(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        // The same "not found" for a document that doesn't exist and one the caller may not see.
        if (documentService.visibleItems(List.of(documentId)).isEmpty()) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        // Two pins at once must not both squeeze under the limit: one user's pins are counted
        // and added one request at a time.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "pins:" + userId), rs -> {
                });
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId).addValue("user", userId).addValue("document", documentId);

        Integer already = jdbc.queryForObject("select count(*) from document_pins where user_id = :user "
                + "and document_id = :document", params, Integer.class);
        if (already != null && already > 0) {
            return;
        }
        int limit = limit(tenantId);
        Integer count = jdbc.queryForObject("select count(*) from document_pins where tenant_id = :tenant "
                + "and user_id = :user", params, Integer.class);
        if (count != null && count >= limit) {
            throw new FolderException(HttpStatus.CONFLICT, "PIN_LIMIT_REACHED",
                    "You can pin up to " + limit + " documents. Unpin one to pin another.");
        }
        jdbc.update("insert into document_pins (tenant_id, user_id, document_id) values (:tenant, :user, :document)",
                params);
    }

    /** Unpins a document. Quietly does nothing when it wasn't pinned. */
    @Transactional
    public void unpin(UUID documentId) {
        jdbc.update("delete from document_pins where tenant_id = :tenant and user_id = :user and document_id = :document",
                new MapSqlParameterSource().addValue("tenant", TenantContext.getTenantId())
                        .addValue("user", TenantContext.getUserId()).addValue("document", documentId));
    }

    @Transactional(readOnly = true)
    public int limit() {
        return limit(TenantContext.getTenantId());
    }

    private int limit(UUID tenantId) {
        List<Integer> rows = jdbc.queryForList("select max_pins from pin_settings where tenant_id = :tenant",
                new MapSqlParameterSource("tenant", tenantId), Integer.class);
        return rows.isEmpty() ? defaultMax : rows.get(0);
    }

    /**
     * How many documents one user may pin in the caller's organisation. Lowering it does not remove
     * anyone's existing pins - it only stops them adding more until they are under it again.
     * Audited as PIN_LIMIT_CHANGED.
     */
    @Transactional
    public int setLimit(int max) {
        if (max < MIN_LIMIT || max > MAX_LIMIT) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PIN_LIMIT_INVALID",
                    "Allow between " + MIN_LIMIT + " and " + MAX_LIMIT + " pinned documents per person.");
        }
        UUID tenantId = TenantContext.getTenantId();
        int before = limit(tenantId);
        jdbc.update("""
                insert into pin_settings (tenant_id, max_pins, updated_at, updated_by)
                values (:tenant, :max, now(), :user)
                on conflict (tenant_id) do update set max_pins = excluded.max_pins,
                    updated_at = now(), updated_by = excluded.updated_by
                """, new MapSqlParameterSource().addValue("tenant", tenantId).addValue("max", max)
                .addValue("user", TenantContext.getUserId()));
        auditService.event(AuditActionCatalog.PIN_LIMIT_CHANGED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .resourceName("Pinned documents limit")
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("maxPins", before))
                .newValue(Map.of("maxPins", max))
                .log();
        return max;
    }
}
