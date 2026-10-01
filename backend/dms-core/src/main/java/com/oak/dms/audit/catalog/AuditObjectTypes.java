package com.oak.dms.audit.catalog;

/**
 * Canonical {@code object_type} string constants for audit events. Every
 * call site that emits an audit event (RoleServiceImpl,
 * RolePermissionServiceImpl, PermissionServiceImpl,
 * PermissionActionServiceImpl, PermissionModuleServiceImpl,
 * UserServiceImpl, AuthService, PasswordResetService, UserRoleServiceImpl,
 * AuditEventServiceImpl, OutboxRelay, TenantServiceImpl,
 * TenantProvisioningService, GlobalExceptionHandler, DocumentTypeServiceImpl,
 * DocumentTypeFieldServiceImpl, MetadataFieldServiceImpl,
 * MetadataFieldOptionServiceImpl, MetadataFieldValidationServiceImpl) uses one of these
 * instead of typing the literal itself, so a typo can't silently create a
 * new, unfiltered object type, and so this class doubles as the
 * definitive list of every object type the audit trail actually produces
 * - the frontend's `OBJECT_TYPE_OPTIONS` (audit-log-filters.tsx) should
 * always match it.
 *
 * DocumentServiceImpl/StorageLocationServiceImpl are also call sites now
 * (DOCUMENT/DOCUMENT_VERSION/STORAGE_LOCATION below).
 */
public final class AuditObjectTypes {

    private AuditObjectTypes() {
    }

    public static final String USER = "USER";
    public static final String ROLE = "ROLE";
    public static final String ROLE_PERMISSION = "ROLE_PERMISSION";
    public static final String PERMISSION = "PERMISSION";
    public static final String PERMISSION_ACTION = "PERMISSION_ACTION";
    public static final String PERMISSION_MODULE = "PERMISSION_MODULE";
    public static final String AUDIT_EVENT = "AUDIT_EVENT";
    public static final String OUTBOX_EVENT = "OUTBOX_EVENT";
    public static final String TENANT = "TENANT";
    public static final String DOCUMENT_TYPE = "DOCUMENT_TYPE";
    public static final String DOCUMENT_TYPE_SCHEMA = "DOCUMENT_TYPE_SCHEMA";
    public static final String METADATA_FIELD = "METADATA_FIELD";
    public static final String METADATA_FIELD_OPTION = "METADATA_FIELD_OPTION";
    public static final String METADATA_FIELD_VALIDATION = "METADATA_FIELD_VALIDATION";
    public static final String DOCUMENT = "DOCUMENT";
    public static final String DOCUMENT_VERSION = "DOCUMENT_VERSION";
    public static final String STORAGE_LOCATION = "STORAGE_LOCATION";
    /** A malware scan of one upload (scan_results) - the object of every DOCUMENT_SCAN_* event. */
    public static final String SCAN_RESULT = "SCAN_RESULT";
    /** A quarantined infected upload (quarantine_records) - the object of DOCUMENT_QUARANTINED. */
    public static final String QUARANTINE_RECORD = "QUARANTINE_RECORD";
    /** One bulk import (import_batches) - the object of every BULK_IMPORT_* event. */
    public static final String IMPORT_BATCH = "IMPORT_BATCH";
    /** A saved import template (import_templates) - the object of every IMPORT_TEMPLATE_* event. */
    public static final String IMPORT_TEMPLATE = "IMPORT_TEMPLATE";
    /** One bulk export (export_jobs) - the object of every EXPORT_* event. */
    public static final String EXPORT_JOB = "EXPORT_JOB";
    /**
     * Not a business entity like every other constant here - the "object"
     * of an ACCESS_DENIED event (see AuditActionCatalog) is the blocked
     * HTTP request itself (method + path, captured as resourceName),
     * since a generic {@code @PreAuthorize} failure has no way to know
     * which specific business object the caller was trying to reach.
     */
    public static final String ENDPOINT = "ENDPOINT";
}
