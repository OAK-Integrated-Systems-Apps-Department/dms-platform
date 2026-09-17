package com.oak.dms.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.dto.AuditEventDetailResponse;
import com.oak.dms.audit.dto.AuditEventResponse;
import com.oak.dms.audit.dto.AuditEventSearchCriteria;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.common.exception.TenantMismatchException;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * See {@link AuditEventService}. {@link #search}/{@link #getById} always
 * force the caller's own tenant, never reading one from the request;
 * {@link #searchAcrossTenants}/{@link #getByIdAcrossTenants} apply no
 * tenant restriction beyond whatever the (already-authorized-elsewhere)
 * caller explicitly asked for.
 */
@Service
@RequiredArgsConstructor
public class AuditEventServiceImpl implements AuditEventService {

    private static final String RESOURCE = "AuditEvent";

    /** Newest-first, capped so an unbounded filter (or none at all) can't try to load the entire trail into memory. */
    private static final int EXPORT_ROW_LIMIT = 10_000;

    private static final List<String> CSV_HEADERS = List.of(
            "occurredAt", "action", "module", "objectType", "objectId", "resourceName",
            "actorType", "actorId", "actorDisplayName", "outcome", "failureReason",
            "severity", "ipAddress", "sessionId", "correlationId", "recordHash"
    );

    private final AuditEventRepository auditEventRepository;
    private final AuditEventMapper auditEventMapper;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> search(AuditEventSearchCriteria criteria, Pageable pageable) {
        Specification<AuditEvent> spec = AuditEventSpecifications.tenantId(TenantContext.getTenantId())
                .and(buildFilterSpec(criteria));
        return auditEventRepository.findAll(spec, pageable).map(auditEventMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditEventDetailResponse getById(UUID id) {
        AuditEvent event = findById(id);
        // Objects.equals, not .equals: a platform-level event's tenantId
        // is null, which must mismatch every real tenant rather than NPE
        // or (worse) accidentally match another null.
        if (!Objects.equals(event.getTenantId(), TenantContext.getTenantId())) {
            throw new TenantMismatchException(RESOURCE, id);
        }
        return toDetailResponse(event);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> searchAcrossTenants(UUID tenantId, AuditEventSearchCriteria criteria, Pageable pageable) {
        Specification<AuditEvent> spec = AuditEventSpecifications.tenantId(tenantId)
                .and(buildFilterSpec(criteria));
        return auditEventRepository.findAll(spec, pageable).map(auditEventMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditEventDetailResponse getByIdAcrossTenants(UUID id) {
        return toDetailResponse(findById(id));
    }

    @Override
    // NOT readOnly: the AUDIT_LOG_EXPORTED write below joins this same
    // transaction (see AuditService#persist's @Transactional) - a
    // readOnly transaction here would make Postgres reject that insert.
    @Transactional
    public String exportCsv(AuditEventSearchCriteria criteria) {
        Specification<AuditEvent> spec = AuditEventSpecifications.tenantId(TenantContext.getTenantId())
                .and(buildFilterSpec(criteria));
        Pageable pageable = PageRequest.of(0, EXPORT_ROW_LIMIT, Sort.by(Sort.Direction.DESC, "occurredAt"));
        List<AuditEvent> rows = auditEventRepository.findAll(spec, pageable).getContent();

        String csv = toCsv(rows);

        auditService.event("AUDIT_LOG_EXPORTED")
                .objectType("AUDIT_EVENT")
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("rowCount", rows.size()))
                .log();

        return csv;
    }

    private String toCsv(List<AuditEvent> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", CSV_HEADERS)).append('\n');
        for (AuditEvent event : rows) {
            csv.append(csvRow(event)).append('\n');
        }
        return csv.toString();
    }

    private String csvRow(AuditEvent event) {
        return String.join(",",
                csvField(event.getOccurredAt()),
                csvField(event.getAction()),
                csvField(event.getModule()),
                csvField(event.getObjectType()),
                csvField(event.getObjectId()),
                csvField(event.getResourceName()),
                csvField(event.getActorType()),
                csvField(event.getActorId()),
                csvField(event.getActorDisplayName()),
                csvField(event.getOutcome()),
                csvField(event.getFailureReason()),
                csvField(event.getSeverity()),
                csvField(event.getIpAddress()),
                csvField(event.getSessionId()),
                csvField(event.getCorrelationId()),
                csvField(event.getRecordHash()));
    }

    /** RFC 4180-style quoting: only wraps a field in quotes (doubling any internal quote) if it actually contains a comma, quote, or newline. */
    private String csvField(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private Specification<AuditEvent> buildFilterSpec(AuditEventSearchCriteria criteria) {
        return AuditEventSpecifications.modules(criteria.modules())
                .and(AuditEventSpecifications.actions(criteria.actions()))
                .and(AuditEventSpecifications.actorIds(criteria.actorIds()))
                .and(AuditEventSpecifications.objectTypes(criteria.objectTypes()))
                .and(AuditEventSpecifications.objectId(criteria.objectId()))
                .and(AuditEventSpecifications.outcome(criteria.outcome()))
                .and(AuditEventSpecifications.severity(criteria.severity()))
                .and(AuditEventSpecifications.occurredBetween(criteria.occurredFrom(), criteria.occurredTo()))
                .and(AuditEventSpecifications.correlationId(criteria.correlationId()));
    }

    private AuditEvent findById(UUID id) {
        return auditEventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private AuditEventDetailResponse toDetailResponse(AuditEvent event) {
        AuditEventResponse summary = auditEventMapper.toResponse(event);
        return new AuditEventDetailResponse(
                summary.id(), summary.tenantId(), summary.actorId(), summary.actorType(), summary.actorDisplayName(),
                summary.impersonatedBy(), summary.action(), summary.module(), summary.objectType(), summary.objectId(),
                summary.resourceName(), summary.objectVersion(), summary.outcome(), summary.failureReason(),
                summary.occurredAt(), toGenericValue(event.getPreviousValue()), toGenericValue(event.getNewValue()),
                summary.reason(), summary.sessionId(), summary.authMethod(), summary.permissionCode(),
                summary.dataClassification(), summary.legalHoldFlag(), summary.ipAddress(), event.getUserAgent(),
                summary.clientType(), summary.correlationId(), summary.requestId(), summary.severity(),
                summary.recordHash(), summary.previousHash());
    }

    /**
     * previousValue/newValue are stored as already-serialized JSON strings
     * (see AuditEvent) - parsed here into a plain Object graph (nested
     * LinkedHashMap/ArrayList/String/Number/Boolean/null), not a
     * {@code JsonNode}, so the API response embeds actual JSON rather
     * than a JSON string containing escaped JSON. Deliberately NOT
     * {@code objectMapper.readTree(json)}: this app's actual HTTP
     * message-converter ObjectMapper is a different Jackson major version
     * than {@code com.fasterxml.jackson.databind}, and doesn't recognize
     * that package's {@code JsonNode} as a tree type at all - see
     * {@link com.oak.dms.audit.dto.AuditEventDetailResponse}'s doc
     * comment for the exact symptom this caused (a real bug hit during
     * end-to-end testing). A generic Object graph has no such ambiguity.
     */
    private Object toGenericValue(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored audit value is not valid JSON", e);
        }
    }
}
