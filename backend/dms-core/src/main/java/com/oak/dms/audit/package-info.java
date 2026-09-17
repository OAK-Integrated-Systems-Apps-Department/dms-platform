/**
 * The platform's immutable audit trail: {@link com.oak.dms.audit.AuditService},
 * the single write path every other module logs through, and the
 * tenant-scoped/cross-tenant search endpoints
 * ({@link com.oak.dms.audit.AuditEventController},
 * {@link com.oak.dms.audit.PlatformAuditEventController}) that read it
 * back. See {@code docs/audit_events_schema_spec.md} for the field-level
 * design spec and {@code docs/audit-events-implementation-report.md} for
 * what's actually wired up today vs. still a gap.
 */
package com.oak.dms.audit;
