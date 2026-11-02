package com.oak.dms.pin;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.common.sql.LikePatterns;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pinned documents (4.3 Pin Important Documents): a pin has an audience.
 * <p>
 * Whoever may pin chooses, when pinning, who sees it - everyone, everyone except some people, or specific
 * people. There is one kind of pin and no separate "pin for me". Only the person who pinned a document can
 * change who sees it or unpin it: unpinning deletes the caller's own assignment and nobody else's.
 * <p>
 * A pin never grants anything (PID-NFR-003): every read and every pin goes through
 * {@link DocumentService#visibleItems} - the document browser's own access rules - so a pinned document is
 * exactly as visible as it would be anywhere else. A pin the caller placed themselves on a document they can
 * no longer see (access revoked, PID-FR-008) or that has been deleted (PID-FR-009) is removed the next time
 * the pinned section is read. A pin placed by someone else is only hidden then, never deleted - it belongs
 * to the pinner, and a recipient losing access for a while must not destroy it. A document that is merely
 * archived stays, flagged.
 */
@Service
@RequiredArgsConstructor
public class PinService {

    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 200;

    /**
     * Most assignments read for one person's pinned section, newest first. The read is bounded so a
     * section cannot grow without limit as people pin (PID-NFR-005).
     * <p>
     * FOLLOW-UP: nothing yet caps how many pins an organisation can hold, so with many pinners the
     * newest {@value} could crowd out an older pin that is still meant for this person. A per-organisation
     * cap (or a paged section) would close that; left out of this change on purpose.
     */
    static final int MAX_READ = 500;

    /** Who sees a pin. {@code ALL}: everyone except the named people. {@code USERS}: only the named people. */
    public enum AudienceMode {
        ALL, USERS
    }

    /** The audience a pinner chooses; {@code userIds} are exclusions for ALL and inclusions for USERS. */
    public record AudienceRequest(AudienceMode mode, List<UUID> userIds) {
    }

    /** A person named by an audience. */
    public record Member(UUID userId, String displayName, String email) {
    }

    /** The caller's own audience, with the people it names, for editing. */
    public record Audience(AudienceMode mode, List<Member> members) {
    }

    /** A short description of an audience for the pinner's own rows: the mode and how many people it names. */
    public record AudienceSummary(AudienceMode mode, int count) {
    }

    /**
     * One pinned document: the same row the document browser shows, plus who pinned it and when.
     * {@code audience} is filled only for the caller's own pins - it says who they chose.
     */
    public record PinnedDocument(DocumentListItem document, OffsetDateTime pinnedAt, boolean archived,
                                 UUID pinnedBy, String pinnedByName, boolean mine, AudienceSummary audience) {
    }

    /** Every pin the caller can see, newest first, and how many documents they may pin. */
    public record Pins(int limit, List<PinnedDocument> pins) {
    }

    /** One assignment the caller can see (package-private so the merge rule can be tested directly). */
    record Row(UUID assignmentId, UUID documentId, UUID pinnedBy, String pinnedByName,
                       AudienceMode mode, OffsetDateTime pinnedAt, int memberCount) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentService documentService;
    private final AuditService auditService;

    @Value("${dms.pins.default-max:20}")
    private int defaultMax;

    @Transactional
    public Pins list() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("user", userId).addValue("cap", MAX_READ);

        // The pins this person sees: the ones they placed, and the ones whose audience names them.
        // ALL names who is left out, USERS names who is let in.
        List<Row> rows = jdbc.query("""
                select a.assignment_id, a.document_id, a.pinned_by, u.display_name as pinned_by_name,
                       a.audience_mode, a.pinned_at,
                       (select count(*) from document_pin_audience_members c
                         where c.assignment_id = a.assignment_id) as member_count
                from document_pin_assignments a
                join users u on u.user_id = a.pinned_by
                where a.tenant_id = :tenant
                  and (a.pinned_by = :user
                       or (a.audience_mode = 'ALL' and not exists (
                              select 1 from document_pin_audience_members m
                               where m.assignment_id = a.assignment_id and m.user_id = :user))
                       or (a.audience_mode = 'USERS' and exists (
                              select 1 from document_pin_audience_members m
                               where m.assignment_id = a.assignment_id and m.user_id = :user)))
                order by a.pinned_at desc
                limit :cap
                """, params, (rs, i) -> new Row(rs.getObject("assignment_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getObject("pinned_by", UUID.class),
                rs.getString("pinned_by_name"), AudienceMode.valueOf(rs.getString("audience_mode")),
                rs.getObject("pinned_at", OffsetDateTime.class), rs.getInt("member_count")));

        Set<UUID> documentIds = new LinkedHashSet<>();
        rows.forEach(r -> documentIds.add(r.documentId()));
        List<DocumentListItem> visible = documentService.visibleItems(documentIds);
        Map<UUID, DocumentListItem> byId = new HashMap<>();
        visible.forEach(d -> byId.put(d.documentId(), d));

        // PID-FR-008/009: what the caller placed themselves on a document they can't see any more - revoked,
        // deleted - is dropped, not just hidden. Someone else's pin is only hidden.
        List<UUID> gone = rows.stream()
                .filter(r -> userId.equals(r.pinnedBy()) && !byId.containsKey(r.documentId()))
                .map(Row::documentId).distinct().toList();
        if (!gone.isEmpty()) {
            jdbc.update("delete from document_pin_assignments where tenant_id = :tenant and pinned_by = :user "
                    + "and document_id in (:ids)", new MapSqlParameterSource(params.getValues()).addValue("ids", gone));
        }

        Set<UUID> archived = new HashSet<>();
        if (!byId.isEmpty()) {
            jdbc.query("select document_id from documents where tenant_id = :tenant and status = 'archived' "
                    + "and document_id in (:ids)",
                    new MapSqlParameterSource(params.getValues()).addValue("ids", byId.keySet()),
                    rs -> {
                        archived.add(rs.getObject("document_id", UUID.class));
                    });
        }

        return new Pins(limit(tenantId), pinnedDocuments(rows, byId, archived, userId));
    }

    /**
     * One entry per visible document. When more than one person pinned it, the caller's own pin wins;
     * otherwise the earliest pinner is shown.
     */
    static List<PinnedDocument> pinnedDocuments(List<Row> rows, Map<UUID, DocumentListItem> visible,
                                                Set<UUID> archived, UUID userId) {
        Map<UUID, Row> chosen = new LinkedHashMap<>();
        for (Row row : rows) {
            if (visible.containsKey(row.documentId())) {
                chosen.merge(row.documentId(), row, (current, next) -> preferred(current, next, userId));
            }
        }
        List<PinnedDocument> pins = new ArrayList<>();
        for (Row row : chosen.values()) {
            boolean mine = userId.equals(row.pinnedBy());
            DocumentListItem item = visible.get(row.documentId());
            pins.add(new PinnedDocument(item, row.pinnedAt(), archived.contains(item.documentId()),
                    row.pinnedBy(), row.pinnedByName(), mine,
                    mine ? new AudienceSummary(row.mode(), row.memberCount()) : null));
        }
        pins.sort(Comparator.comparing(PinnedDocument::pinnedAt).reversed());
        return pins;
    }

    private static Row preferred(Row current, Row next, UUID userId) {
        if (userId.equals(current.pinnedBy())) {
            return current;
        }
        if (userId.equals(next.pinnedBy())) {
            return next;
        }
        return current.pinnedAt().isAfter(next.pinnedAt()) ? next : current;
    }

    /**
     * Pins a document the caller can see, for the audience they choose - or changes the audience of a pin
     * they already placed. At the limit a new pin is refused with PIN_LIMIT_REACHED (PID-FR-007), which the
     * screen shows as it is; changing the audience of an existing pin never counts against the limit.
     */
    @Transactional
    public void pin(UUID documentId, AudienceRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        AudienceMode mode = request == null ? null : request.mode();
        if (mode == null) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PIN_AUDIENCE_REQUIRED",
                    "Choose who should see this pin.");
        }
        // The pinner always sees their own pin, so leaving themselves out would change nothing: it is dropped
        // rather than stored, and cannot make an otherwise valid audience look empty.
        List<UUID> members = request.userIds() == null ? List.of() : request.userIds().stream().distinct()
                .filter(id -> mode != AudienceMode.ALL || !id.equals(userId)).toList();

        // The same "not found" for a document that doesn't exist and one the caller may not see.
        List<DocumentListItem> found = documentService.visibleItems(List.of(documentId));
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        validateAudience(tenantId, userId, mode, members);

        // Two pins at once must not both squeeze under the limit: one person's pins are counted
        // and added one request at a time.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "pins:" + userId), rs -> {
                });
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", tenantId).addValue("user", userId).addValue("document", documentId);

        List<UUID> existing = jdbc.queryForList("select assignment_id from document_pin_assignments "
                + "where tenant_id = :tenant and document_id = :document and pinned_by = :user", params, UUID.class);
        UUID assignmentId;
        boolean created = existing.isEmpty();
        if (created) {
            int limit = limit(tenantId);
            Integer count = jdbc.queryForObject("select count(*) from document_pin_assignments "
                    + "where tenant_id = :tenant and pinned_by = :user", params, Integer.class);
            if (count != null && count >= limit) {
                throw new FolderException(HttpStatus.CONFLICT, "PIN_LIMIT_REACHED",
                        "You can pin up to " + limit + " documents. Unpin one to pin another.");
            }
            assignmentId = jdbc.queryForObject("insert into document_pin_assignments "
                    + "(tenant_id, document_id, pinned_by, audience_mode) "
                    + "values (:tenant, :document, :user, :mode) returning assignment_id",
                    new MapSqlParameterSource(params.getValues()).addValue("mode", mode.name()), UUID.class);
        } else {
            assignmentId = existing.get(0);
            jdbc.update("update document_pin_assignments set audience_mode = :mode, updated_at = now() "
                    + "where assignment_id = :assignment",
                    new MapSqlParameterSource("mode", mode.name()).addValue("assignment", assignmentId));
            jdbc.update("delete from document_pin_audience_members where assignment_id = :assignment",
                    new MapSqlParameterSource("assignment", assignmentId));
        }
        if (!members.isEmpty()) {
            SqlParameterSource[] batch = members.stream()
                    .map(id -> new MapSqlParameterSource("assignment", assignmentId).addValue("user", id))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("insert into document_pin_audience_members (assignment_id, user_id) "
                    + "values (:assignment, :user)", batch);
        }

        auditService.event(created ? AuditActionCatalog.PIN_ADDED : AuditActionCatalog.PIN_AUDIENCE_CHANGED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(found.get(0).title())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("mode", mode.name(), "people", members.size()))
                .log();
    }

    /**
     * Rejects an audience that cannot work: USERS naming nobody, a name from another organisation, or ALL
     * that leaves out everyone else. The pinner always sees their own pin, so they are never counted as
     * "somebody who would see it": an ALL pin that leaves out every other person would be seen by no one
     * but its pinner, which is almost certainly a mistake. (An ALL pin that leaves nobody out is fine even in
     * an organisation of one.)
     */
    private void validateAudience(UUID tenantId, UUID pinnerId, AudienceMode mode, List<UUID> members) {
        if (mode == AudienceMode.USERS && members.isEmpty()) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PIN_AUDIENCE_EMPTY",
                    "Choose at least one person, or pin it for all users.");
        }
        if (members.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("ids", members);
        Integer known = jdbc.queryForObject("select count(*) from users where tenant_id = :tenant "
                + "and user_id in (:ids)", params, Integer.class);
        if (known == null || known != members.size()) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PIN_AUDIENCE_INVALID",
                    "Some of the people chosen are not in this organisation.");
        }
        if (mode == AudienceMode.ALL) {
            Integer left = jdbc.queryForObject("select count(*) from users where tenant_id = :tenant "
                    + "and status = 'active' and user_id <> :pinner and user_id not in (:ids)",
                    new MapSqlParameterSource(params.getValues()).addValue("pinner", pinnerId), Integer.class);
            if (left == null || left == 0) {
                throw new FolderException(HttpStatus.BAD_REQUEST, "PIN_AUDIENCE_EMPTY",
                        "Nobody else would see this pin. Leave at least one other person in.");
            }
        }
    }

    /** Unpins the caller's own pin on a document, for everyone it was shown to. Quietly does nothing when they had none. */
    @Transactional
    public void unpin(UUID documentId) {
        int removed = jdbc.update("delete from document_pin_assignments where tenant_id = :tenant "
                + "and pinned_by = :user and document_id = :document",
                new MapSqlParameterSource().addValue("tenant", TenantContext.getTenantId())
                        .addValue("user", TenantContext.getUserId()).addValue("document", documentId));
        if (removed > 0) {
            auditService.event(AuditActionCatalog.PIN_REMOVED)
                    .objectType(AuditObjectTypes.DOCUMENT)
                    .objectId(documentId)
                    .outcome(AuditOutcome.SUCCESS)
                    .log();
        }
    }

    /** The audience of the caller's own pin on a document, with the people it names. */
    @Transactional(readOnly = true)
    public Audience audience(UUID documentId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", TenantContext.getTenantId())
                .addValue("user", TenantContext.getUserId()).addValue("document", documentId);
        List<AudienceMode> modes = jdbc.query("select audience_mode from document_pin_assignments "
                + "where tenant_id = :tenant and document_id = :document and pinned_by = :user", params,
                (rs, i) -> AudienceMode.valueOf(rs.getString("audience_mode")));
        if (modes.isEmpty()) {
            throw new ResourceNotFoundException("Pin", documentId);
        }
        List<Member> members = jdbc.query("""
                select u.user_id, u.display_name, u.email
                from document_pin_audience_members m
                join document_pin_assignments a on a.assignment_id = m.assignment_id
                join users u on u.user_id = m.user_id
                where a.tenant_id = :tenant and a.document_id = :document and a.pinned_by = :user
                order by u.display_name
                """, params, (rs, i) -> new Member(rs.getObject("user_id", UUID.class),
                rs.getString("display_name"), rs.getString("email")));
        return new Audience(modes.get(0), members);
    }

    /**
     * People the caller can choose from when picking an audience: active people in their organisation whose
     * name or email contains the search text. Open to anyone who may pin - it does not depend on the user
     * administration permissions, because choosing an audience should not need them.
     */
    @Transactional(readOnly = true)
    public List<Member> candidates(String query, int limit) {
        String pattern = LikePatterns.contains(query == null ? "" : query.strip());
        return jdbc.query("""
                select user_id, display_name, email from users
                where tenant_id = :tenant and status = 'active'
                  and (lower(display_name) like :q or lower(email) like :q)
                order by display_name
                limit :limit
                """, new MapSqlParameterSource().addValue("tenant", TenantContext.getTenantId())
                .addValue("q", pattern).addValue("limit", Math.max(1, Math.min(limit, 50))),
                (rs, i) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                        rs.getString("email")));
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
     * How many documents one person may pin in the caller's organisation. Lowering it does not remove
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
