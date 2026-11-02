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
 * (DOCUMENT/DOCUMENT_VERSION/STORAGE_LOCATION below), as are
 * DocumentSignatureService/WorkflowSignatureListener/SigningKeyService/
 * DocumentSignatureLockService (DOCUMENT_SIGNATURE/SIGNING_KEY below).
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
    /** A folder of the shared folder tree (folders) - the object of every FOLDER_* event. */
    public static final String FOLDER = "FOLDER";
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
    /** A watermark template (watermark_templates) - the object of every WATERMARK_TEMPLATE_* event. */
    public static final String WATERMARK_TEMPLATE = "WATERMARK_TEMPLATE";
    /** One requested watermarked copy (watermark_jobs) - the object of the other WATERMARK_* events. */
    public static final String WATERMARK_JOB = "WATERMARK_JOB";
    /** A tenant's download-watermarking policy (tenant_watermark_settings) - object id is the tenant id. */
    public static final String TENANT_WATERMARK_SETTINGS = "TENANT_WATERMARK_SETTINGS";
    /** The master key that wraps every stored object's data key - the object of every ENCRYPTION_* event. */
    public static final String ENCRYPTION_KEY = "ENCRYPTION_KEY";

    /** One template-based bulk import (migration_batches) - the object of every BULK_IMPORT_* event. */
    public static final String MIGRATION_BATCH = "MIGRATION_BATCH";
    /** A department (V43) - the object of every DEPARTMENT_* event. */
    public static final String DEPARTMENT = "DEPARTMENT";
    /** One notification delivery (notification_deliveries) - the object of NOTIFICATION_* events (3.2). */
    public static final String NOTIFICATION_DELIVERY = "NOTIFICATION_DELIVERY";
    /** A notification category setting/preference (3.2) - the object of setting/preference-change events. */
    public static final String NOTIFICATION_SETTING = "NOTIFICATION_SETTING";
    /** One workflow template version (workflow_templates) - every WORKFLOW_TEMPLATE_* event. */
    public static final String WORKFLOW_TEMPLATE = "WORKFLOW_TEMPLATE";
    /** One workflow run (workflow_instances) - submit/stage/outcome/cancel/version events. */
    public static final String WORKFLOW_INSTANCE = "WORKFLOW_INSTANCE";
    /** One approver task (workflow_tasks) - claim/release/approve/reject events. */
    public static final String WORKFLOW_TASK = "WORKFLOW_TASK";
    /** One information request (workflow_info_requests) - every WORKFLOW_INFO_* event. */
    public static final String WORKFLOW_INFO_REQUEST = "WORKFLOW_INFO_REQUEST";
    /** Evidence attached to a workflow run (workflow_supporting_documents). */
    public static final String WORKFLOW_SUPPORTING_DOCUMENT = "WORKFLOW_SUPPORTING_DOCUMENT";
    /** A comment on a document (document_comments) - every DOCUMENT_COMMENT_* event. */
    public static final String DOCUMENT_COMMENT = "DOCUMENT_COMMENT";
    /** One signature (document_signatures, V105) - the object of every DOCUMENT_SIGNATURE_* event except LOCKED/UNLOCKED, whose object is the DOCUMENT itself. */
    public static final String DOCUMENT_SIGNATURE = "DOCUMENT_SIGNATURE";
    /** One user's signing key (user_signing_keys, V105) - the object of every SIGNING_KEY_* event. */
    public static final String SIGNING_KEY = "SIGNING_KEY";
    /** One user's saved visual signature (user_visual_signatures, V109) - cosmetic, the object of VISUAL_SIGNATURE_UPDATED. */
    public static final String VISUAL_SIGNATURE = "VISUAL_SIGNATURE";
    /** A link between two documents (document_relationships) - every RELATIONSHIP_* event except the type ones. */
    public static final String DOCUMENT_RELATIONSHIP = "DOCUMENT_RELATIONSHIP";
    /** A relationship type (relationship_types) - every RELATIONSHIP_TYPE_* event. */
    public static final String RELATIONSHIP_TYPE = "RELATIONSHIP_TYPE";
    /** A bulk/offboarding transfer run (ownership_transfer_batches). */
    public static final String OWNERSHIP_BATCH = "OWNERSHIP_BATCH";

    /**
     * Not a business entity like every other constant here - the "object"
     * of an ACCESS_DENIED event (see AuditActionCatalog) is the blocked
     * HTTP request itself (method + path, captured as resourceName),
     * since a generic {@code @PreAuthorize} failure has no way to know
     * which specific business object the caller was trying to reach.
     */
    public static final String ENDPOINT = "ENDPOINT";
}
