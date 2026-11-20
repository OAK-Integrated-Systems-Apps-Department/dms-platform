package com.oak.dms.sharing;

import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.filter.AuditRequestContext;
import com.oak.dms.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the sharing log (SEA-FR-006, SEA-NFR-002): one {@link ShareEvent} row per thing that happened, with who,
 * from which address, and when - plus, for the events that matter to an auditor, the organisation's tamper-evident
 * audit trail. The log row is the report; the audit row is the evidence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ShareEventLog {

    static final String LINK_CREATED = "link_created";
    static final String LINK_OPENED = "link_opened";
    static final String LINK_VIEWED = "link_viewed";
    static final String LINK_DOWNLOADED = "link_downloaded";
    static final String LINK_REVOKED = "link_revoked";
    static final String LINK_EXPIRED = "link_expired";
    static final String PASSWORD_FAILED = "password_failed";
    static final String LINK_LOCKED = "link_locked";
    static final String LINK_FLAGGED = "link_flagged";
    static final String LINK_REVIEW_CLEARED = "link_review_cleared";
    static final String GUEST_INVITED = "guest_invited";
    static final String GUEST_GRANT_CREATED = "guest_grant_created";
    static final String GUEST_GRANT_REVOKED = "guest_grant_revoked";
    static final String GUEST_SIGNED_IN = "guest_signed_in";
    static final String GUEST_VIEWED = "guest_viewed";
    static final String GUEST_DOWNLOADED = "guest_downloaded";
    static final String GUEST_DISABLED = "guest_disabled";

    private final ShareEventRepository repository;
    private final AuditService auditService;

    /** Who and what an event is about; everything but the tenant and type may be null. */
    record Subject(UUID tenantId, String type, UUID linkId, UUID guestId, String targetType, UUID targetId,
                   String targetName, UUID actorUserId, String actor, String detail) {
    }

    void record(Subject s) {
        AuditRequestContext ctx = AuditRequestContext.currentOrNull();
        repository.save(ShareEvent.builder()
                .tenantId(s.tenantId()).eventType(s.type()).linkId(s.linkId()).guestId(s.guestId())
                .targetType(s.targetType()).targetId(s.targetId()).targetName(cut(s.targetName(), 500))
                .actorUserId(s.actorUserId()).actorLabel(cut(s.actor() == null ? "Anonymous" : s.actor(), 320))
                .ipAddress(ctx == null ? null : cut(ctx.getIpAddress(), 64))
                .userAgent(ctx == null ? null : cut(ctx.getUserAgent(), 300))
                .detail(cut(s.detail(), 500))
                .build());
    }

    /**
     * Mirrors an event into the audit trail. {@code byUser} = a signed-in user did it (the request's own actor);
     * otherwise it is attributed to the system with the real identity - a recipient's address, a guest's email -
     * in the payload, because a recipient has no user id to put there.
     */
    void audit(String action, UUID tenantId, String objectType, UUID objectId, String name, boolean byUser,
               String actor, Map<String, Object> details) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(details == null ? Map.of() : details);
            if (!byUser) {
                payload.put("actor", actor == null ? "Anonymous" : actor);
                AuditRequestContext ctx = AuditRequestContext.currentOrNull();
                if (ctx != null && ctx.getIpAddress() != null) {
                    payload.put("ip", ctx.getIpAddress());
                }
            }
            var builder = auditService.event(action).tenantId(tenantId).objectType(objectType).objectId(objectId)
                    .resourceName(name).outcome(AuditOutcome.SUCCESS).newValue(payload);
            if (!byUser) {
                builder.systemActor();
            }
            builder.log();
        } catch (RuntimeException e) {
            // The sharing log row above is the record of what happened; a failed mirror must not undo or block a
            // recipient's access or an owner's revocation.
            log.warn("Could not write audit event {} for {}: {}", action, objectId, e.getMessage());
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
