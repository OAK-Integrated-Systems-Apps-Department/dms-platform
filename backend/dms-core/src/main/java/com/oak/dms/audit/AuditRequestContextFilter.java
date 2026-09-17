package com.oak.dms.audit;

import com.oak.dms.security.auth.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Runs once per request (after {@code TenantContextFilter}, so both JWT
 * validation and tenant/actor resolution have already happened) and binds
 * {@link AuditRequestContext} for the duration of the request, cleared
 * afterwards - same shape as {@code TenantContextFilter}.
 *
 * request_id is always freshly generated here; it is never read from any
 * inbound header, so a caller cannot influence it (see the correlation_id
 * note in docs/audit_events_schema_spec.md - the same reasoning applies
 * to request_id, which is "generated automatically per HTTP request").
 *
 * correlation_id defaults to request_id (this request starts its own,
 * unrelated sequence) unless the caller supplies an {@code X-Correlation-Id}
 * this server itself issued to the SAME tenant/actor on a previous
 * response - see {@link AuditCorrelationTokenCodec}. A missing, forged, or
 * foreign (another tenant/actor's) token is silently ignored, exactly as if
 * it were absent: a bad header must never fail the request, only fail to
 * continue a sequence. Every authenticated response carries a freshly
 * signed {@code X-Correlation-Id} for whichever correlation_id this request
 * ended up using, so any client can choose to thread it into a subsequent
 * related call (see RolesStore#addRole on the frontend for the first real
 * user of this: create-role, then assign its initial permissions, both
 * grouped under one correlation_id). Only issued/honored for an
 * authenticated, tenant-bound request - there's no meaningful actor/tenant
 * to bind the token to otherwise (e.g. the login endpoints, which run
 * before TenantContext exists).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRequestContextFilter extends OncePerRequestFilter {

    private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final AuditCorrelationTokenCodec correlationTokenCodec;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            UUID requestId = UUID.randomUUID();
            UUID correlationId = resolveCorrelationId(request, response, requestId);
            AuditRequestContext.bind(
                    requestId,
                    correlationId,
                    resolveClientIp(request),
                    request.getHeader("User-Agent"),
                    resolveClientType(request),
                    resolveSessionId());
            chain.doFilter(request, response);
        } finally {
            AuditRequestContext.clear();
        }
    }

    private UUID resolveCorrelationId(HttpServletRequest request, HttpServletResponse response, UUID requestId) {
        UUID tenantId = currentTenantIdOrNull();
        UUID actorId = currentActorIdOrNull();
        if (actorId == null) {
            // No bound identity to issue/verify a token against (e.g. the
            // public login endpoints) - every event this request writes
            // will fall back to AuditService's own SYSTEM actor default
            // anyway, so there's nothing meaningful to continue or hand
            // out a continuation token for.
            return requestId;
        }

        UUID correlationId = requestId;
        String suppliedToken = request.getHeader(CORRELATION_HEADER);
        if (suppliedToken != null && !suppliedToken.isBlank()) {
            correlationId = correlationTokenCodec.verify(suppliedToken, tenantId, actorId).orElse(requestId);
        }
        response.setHeader(CORRELATION_HEADER, correlationTokenCodec.issue(correlationId, tenantId, actorId));
        return correlationId;
    }

    private UUID currentTenantIdOrNull() {
        try {
            return TenantContext.getTenantId();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private UUID currentActorIdOrNull() {
        try {
            return TenantContext.getUserId();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private AuditClientType resolveClientType(HttpServletRequest request) {
        String header = request.getHeader(CLIENT_TYPE_HEADER);
        if (header == null) {
            return AuditClientType.WEB;
        }
        try {
            return AuditClientType.valueOf(header.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognized {} header value '{}' - defaulting to WEB", CLIENT_TYPE_HEADER, header);
            return AuditClientType.WEB;
        }
    }

    /**
     * Keycloak's access token carries the session as either "sid" (the
     * standard OIDC claim) or "session_state" (Keycloak's older/additional
     * claim), depending on realm/client settings - checked in that order.
     * Absent entirely for a request with no JWT (e.g. a public endpoint).
     */
    private UUID resolveSessionId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        String sid = jwt.getClaimAsString("sid");
        if (sid == null) {
            sid = jwt.getClaimAsString("session_state");
        }
        if (sid == null) {
            return null;
        }
        try {
            return UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            log.warn("JWT session claim '{}' is not a valid UUID - leaving audit session_id null", sid);
            return null;
        }
    }
}
