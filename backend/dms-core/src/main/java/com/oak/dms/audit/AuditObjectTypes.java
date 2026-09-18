package com.oak.dms.audit;

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
    /**
     * Not a business entity like every other constant here - the "object"
     * of an ACCESS_DENIED event (see AuditActionCatalog) is the blocked
     * HTTP request itself (method + path, captured as resourceName),
     * since a generic {@code @PreAuthorize} failure has no way to know
     * which specific business object the caller was trying to reach.
     */
    public static final String ENDPOINT = "ENDPOINT";
}
