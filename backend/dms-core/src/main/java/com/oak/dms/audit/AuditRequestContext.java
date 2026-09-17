package com.oak.dms.audit;

import java.util.UUID;

/**
 * Request-scoped holder for the audit fields that only make sense
 * derived from the live HTTP request, populated by
 * {@link AuditRequestContextFilter} and read by {@link AuditService}.
 *
 * request_id and correlation_id are both resolved server-side and never
 * accepted from client input at face value (see the correlation_id note
 * in docs/audit_events_schema_spec.md) - an untrusted caller must not be
 * able to choose how its own events get grouped. request_id is always a
 * fresh id, unique to this one request. correlation_id defaults to that
 * same fresh id (this request starts its own sequence) unless the caller
 * supplied a signed {@code X-Correlation-Id} token this server itself
 * issued to it on an earlier response - see
 * {@link AuditCorrelationTokenCodec} and {@link AuditRequestContextFilter}
 * for how a continuation is verified before being honored. Every audit
 * event written during one HTTP request still shares that one request's
 * resolved correlation_id.
 *
 * Unbound (all null) for work with no HTTP request - a scheduled job or
 * background task - which is not an error: AuditService falls back to
 * generating a fresh id inline for those instead of requiring context.
 */
public final class AuditRequestContext {

    private static final ThreadLocal<AuditRequestContext> CURRENT = new ThreadLocal<>();

    private final UUID requestId;
    private final UUID correlationId;
    private final String ipAddress;
    private final String userAgent;
    private final AuditClientType clientType;
    private final UUID sessionId;

    private AuditRequestContext(UUID requestId, UUID correlationId, String ipAddress, String userAgent,
                                 AuditClientType clientType, UUID sessionId) {
        this.requestId = requestId;
        this.correlationId = correlationId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.clientType = clientType;
        this.sessionId = sessionId;
    }

    static void bind(UUID requestId, UUID correlationId, String ipAddress, String userAgent,
                      AuditClientType clientType, UUID sessionId) {
        CURRENT.set(new AuditRequestContext(requestId, correlationId, ipAddress, userAgent, clientType, sessionId));
    }

    static void clear() {
        CURRENT.remove();
    }

    static AuditRequestContext currentOrNull() {
        return CURRENT.get();
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public AuditClientType getClientType() {
        return clientType;
    }

    public UUID getSessionId() {
        return sessionId;
    }
}
