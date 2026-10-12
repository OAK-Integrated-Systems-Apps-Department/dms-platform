package com.oak.dms.audit.filter;

import com.oak.dms.audit.enums.AuditClientType;

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
 * A background job that carries on a sequence a request started (a queued
 * export, say) binds that sequence's correlation_id for its own duration
 * with {@link #bindBackground(UUID)} instead.
 */
public final class AuditRequestContext {

    private static final ThreadLocal<AuditRequestContext> CURRENT = new ThreadLocal<>();

    private final UUID requestId;
    private final UUID correlationId;
    private final String ipAddress;
    private final String userAgent;
    private final AuditClientType clientType;
    private final String sessionId;

    private AuditRequestContext(UUID requestId, UUID correlationId, String ipAddress, String userAgent,
                                 AuditClientType clientType, String sessionId) {
        this.requestId = requestId;
        this.correlationId = correlationId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.clientType = clientType;
        this.sessionId = sessionId;
    }

    static void bind(UUID requestId, UUID correlationId, String ipAddress, String userAgent,
                      AuditClientType clientType, String sessionId) {
        CURRENT.set(new AuditRequestContext(requestId, correlationId, ipAddress, userAgent, clientType, sessionId));
    }

    static void clear() {
        CURRENT.remove();
    }

    /**
     * Binds {@code correlationId} for background work - a message listener
     * or scheduled job - so every audit event it writes, including ones
     * written deep inside other modules, joins the sequence the original
     * request started. The id must come from the server's own records (the
     * job row the request created), never from message or client input.
     * Each binding gets a fresh request_id; ip address, user agent, client
     * type and session are null, as there is no request.
     *
     * <p>Always use try-with-resources. Closing restores whatever was bound
     * before (or unbinds if nothing was) rather than clearing outright, so a
     * nested binding can't wipe its caller's, and a pooled listener or
     * scheduler thread never carries one job's id into the next.
     *
     * <pre>{@code
     * try (AuditRequestContext.Scope scope = AuditRequestContext.bindBackground(job.getCorrelationId())) {
     *     // work that writes audit events
     * }
     * }</pre>
     */
    public static Scope bindBackground(UUID correlationId) {
        AuditRequestContext previous = CURRENT.get();
        CURRENT.set(new AuditRequestContext(UUID.randomUUID(), correlationId, null, null, null, null));
        return new Scope(previous);
    }

    /** Undoes one {@link #bindBackground(UUID)}; closing it again does nothing. */
    public static final class Scope implements AutoCloseable {

        private final AuditRequestContext previous;
        private boolean closed;

        private Scope(AuditRequestContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }

    public static AuditRequestContext currentOrNull() {
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

    public String getSessionId() {
        return sessionId;
    }
}
