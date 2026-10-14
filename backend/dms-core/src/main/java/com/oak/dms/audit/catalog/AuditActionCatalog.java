package com.oak.dms.audit.catalog;

import com.oak.dms.audit.enums.AuditSeverity;

import java.util.Map;

/**
 * The event catalogue referenced throughout
 * docs/audit_events_schema_spec.md: the single place that decides an
 * action's module, severity, and whether a reason is mandatory for it.
 * AuditService looks up every action here rather than accepting those
 * three things as caller-supplied parameters, so severity and
 * reason-mandatoriness can never drift per screen/controller.
 *
 * Module codes match permission_modules.module_code exactly (see
 * V7__seed_permission_modules_and_actions.sql):
 *  - ASM  = Access and Security Management (roles, permissions, auth policy, security events)
 *  - OTUA = Organization, Tenant and User Administration (user administration)
 *  - ABDR = Comprehensive Audit Management (the audit trail itself, e.g. exporting it)
 *  - SHAD = System Health and Administration Dashboard (platform infrastructure status, e.g. the outbox relay)
 *  - MDTM = Metadata and Document-Type Management (document types, the metadata field catalog, and the field-mapping matrix that ties them together)
 *  - WA   = Workflow Automation (approval templates and their runtime)
 *
 * reasonRequired would, per the spec's own example, be true for
 * destructive/access-revoking actions ("mandatory for legal hold /
 * permanent deletion"). USER_DEACTIVATED is the first one wired up this
 * way - UserController#deactivateUser takes a DeactivateUserRequest with a
 * mandatory reason field, sourced from a required text input in the
 * Activate/Deactivate confirmation dialog (see UserStatusDialog on the
 * frontend). Every other delete/revoke/deactivate endpoint below is still
 * false, because none of RoleController/RolePermissionController/
 * PermissionController's equivalent endpoints accept a reason field from
 * the caller yet (confirmed by inspection - no DTO on any of those
 * endpoints has one). Marking one of those true without a request field to
 * source it from would make every such call fail every time. Flip an entry
 * to true only once its endpoint's request DTO actually carries a reason -
 * AuditEventBuilder.reason(...) already supports passing one through
 * whenever it does.
 *
 * New actions must be added here before AuditService will accept them -
 * see {@link #require}.
 */
public final class AuditActionCatalog {

    // Action-code constants - every auditService.event(...) call site across
    // the codebase should reference one of these instead of retyping the
    // literal, so a typo becomes a compile error instead of a runtime
    // IllegalStateException from require() below.
    public static final String ROLE_CREATED = "ROLE_CREATED";
    public static final String ROLE_UPDATED = "ROLE_UPDATED";
    public static final String ROLE_DELETED = "ROLE_DELETED";
    public static final String ROLE_PERMISSION_ASSIGNED = "ROLE_PERMISSION_ASSIGNED";
    public static final String ROLE_PERMISSION_REVOKED = "ROLE_PERMISSION_REVOKED";
    public static final String PERMISSION_CREATED = "PERMISSION_CREATED";
    public static final String PERMISSION_UPDATED = "PERMISSION_UPDATED";
    public static final String PERMISSION_DELETED = "PERMISSION_DELETED";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    /** The holder changed their own password from inside the DMS (distinct from a reset via emailed link). */
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_ROLE_ASSIGNED = "USER_ROLE_ASSIGNED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String USER_REACTIVATED = "USER_REACTIVATED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String AUDIT_LOG_EXPORTED = "AUDIT_LOG_EXPORTED";
    public static final String OUTBOX_EVENT_PUBLISH_FAILED = "OUTBOX_EVENT_PUBLISH_FAILED";
    public static final String USER_ROLE_REVOKED = "USER_ROLE_REVOKED";
    public static final String PERMISSION_ACTION_CREATED = "PERMISSION_ACTION_CREATED";
    public static final String PERMISSION_ACTION_UPDATED = "PERMISSION_ACTION_UPDATED";
    public static final String PERMISSION_ACTION_DELETED = "PERMISSION_ACTION_DELETED";
    public static final String PERMISSION_MODULE_CREATED = "PERMISSION_MODULE_CREATED";
    public static final String PERMISSION_MODULE_UPDATED = "PERMISSION_MODULE_UPDATED";
    public static final String PERMISSION_MODULE_DELETED = "PERMISSION_MODULE_DELETED";
    public static final String TENANT_CREATED = "TENANT_CREATED";
    public static final String TENANT_PROVISIONED = "TENANT_PROVISIONED";
    public static final String TENANT_UPDATED = "TENANT_UPDATED";
    public static final String TENANT_DEACTIVATED = "TENANT_DEACTIVATED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String IMPERSONATION_STARTED = "IMPERSONATION_STARTED";
    public static final String IMPERSONATION_ENDED = "IMPERSONATION_ENDED";
    public static final String DOCUMENT_TYPE_CREATED = "DOCUMENT_TYPE_CREATED";
    public static final String DOCUMENT_TYPE_UPDATED = "DOCUMENT_TYPE_UPDATED";
    public static final String DOCUMENT_TYPE_ACTIVATED = "DOCUMENT_TYPE_ACTIVATED";
    public static final String DOCUMENT_TYPE_DEACTIVATED = "DOCUMENT_TYPE_DEACTIVATED";
    public static final String DOCUMENT_TYPE_SCHEMA_PUBLISHED = "DOCUMENT_TYPE_SCHEMA_PUBLISHED";
    public static final String METADATA_FIELD_CREATED = "METADATA_FIELD_CREATED";
    public static final String METADATA_FIELD_UPDATED = "METADATA_FIELD_UPDATED";
    public static final String METADATA_FIELD_ACTIVATED = "METADATA_FIELD_ACTIVATED";
    public static final String METADATA_FIELD_DEACTIVATED = "METADATA_FIELD_DEACTIVATED";
    public static final String METADATA_FIELD_DEPRECATED = "METADATA_FIELD_DEPRECATED";
    public static final String METADATA_FIELD_DELETED = "METADATA_FIELD_DELETED";
    public static final String METADATA_FIELD_OPTION_CREATED = "METADATA_FIELD_OPTION_CREATED";
    public static final String METADATA_FIELD_OPTION_UPDATED = "METADATA_FIELD_OPTION_UPDATED";
    public static final String METADATA_FIELD_OPTION_DELETED = "METADATA_FIELD_OPTION_DELETED";
    public static final String METADATA_FIELD_VALIDATION_CREATED = "METADATA_FIELD_VALIDATION_CREATED";
    public static final String METADATA_FIELD_VALIDATION_UPDATED = "METADATA_FIELD_VALIDATION_UPDATED";
    public static final String METADATA_FIELD_VALIDATION_DELETED = "METADATA_FIELD_VALIDATION_DELETED";
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";
    public static final String DOCUMENT_DOWNLOADED = "DOCUMENT_DOWNLOADED";
    public static final String DOCUMENT_VIEWED = "DOCUMENT_VIEWED";
    public static final String DOCUMENT_DELETED = "DOCUMENT_DELETED";
    public static final String STORAGE_LOCATION_CREATED = "STORAGE_LOCATION_CREATED";
    public static final String DOCUMENT_SCAN_CLEAN = "DOCUMENT_SCAN_CLEAN";
    public static final String DOCUMENT_SCAN_INFECTED = "DOCUMENT_SCAN_INFECTED";
    public static final String DOCUMENT_SCAN_FAILED = "DOCUMENT_SCAN_FAILED";
    public static final String DOCUMENT_SCAN_UNSCANNABLE = "DOCUMENT_SCAN_UNSCANNABLE";
    public static final String DOCUMENT_QUARANTINED = "DOCUMENT_QUARANTINED";
    public static final String QUARANTINE_RELEASED = "QUARANTINE_RELEASED";
    public static final String QUARANTINE_RELEASE_REFUSED = "QUARANTINE_RELEASE_REFUSED";
    public static final String QUARANTINE_OVERRIDE_RELEASED = "QUARANTINE_OVERRIDE_RELEASED";
    public static final String QUARANTINE_DELETED = "QUARANTINE_DELETED";
    public static final String BULK_IMPORT_STARTED = "BULK_IMPORT_STARTED";
    public static final String BULK_IMPORT_COMPLETED = "BULK_IMPORT_COMPLETED";
    public static final String BULK_IMPORT_CANCELLED = "BULK_IMPORT_CANCELLED";

    public static final String IMPORT_FILE_BLOCKED = "IMPORT_FILE_BLOCKED";
    public static final String FOLDER_CREATED = "FOLDER_CREATED";
    public static final String FOLDER_RENAMED = "FOLDER_RENAMED";
    public static final String FOLDER_MOVED = "FOLDER_MOVED";
    public static final String FOLDER_DELETED = "FOLDER_DELETED";
    public static final String FOLDER_ACCESS_CHANGED = "FOLDER_ACCESS_CHANGED";
    public static final String DOCUMENT_ACCESS_CHANGED = "DOCUMENT_ACCESS_CHANGED";
    public static final String ENCRYPTION_KEY_ROTATED = "ENCRYPTION_KEY_ROTATED";
    public static final String ENCRYPTION_KEYS_REWRAPPED = "ENCRYPTION_KEYS_REWRAPPED";
    public static final String ENCRYPTION_BACKFILL_COMPLETED = "ENCRYPTION_BACKFILL_COMPLETED";
    public static final String ENCRYPTION_ROTATION_POLICY_CHANGED = "ENCRYPTION_ROTATION_POLICY_CHANGED";
    public static final String ENCRYPTION_KEY_ROTATION_DUE = "ENCRYPTION_KEY_ROTATION_DUE";
    public static final String ENCRYPTION_TENANT_KEYS_SHREDDED = "ENCRYPTION_TENANT_KEYS_SHREDDED";
    public static final String DOCUMENT_MOVED = "DOCUMENT_MOVED";
    public static final String DOCUMENT_VERSION_RESTORED = "DOCUMENT_VERSION_RESTORED";
    public static final String DOCUMENT_RESTORED = "DOCUMENT_RESTORED";
    public static final String DOCUMENT_PURGED = "DOCUMENT_PURGED";
    public static final String RECYCLE_BIN_RETENTION_CHANGED = "RECYCLE_BIN_RETENTION_CHANGED";
    /** The organisation's cap on how many documents one person may pin (PID). */
    public static final String PIN_LIMIT_CHANGED = "PIN_LIMIT_CHANGED";
    public static final String IMPORT_TEMPLATE_CREATED = "IMPORT_TEMPLATE_CREATED";
    public static final String IMPORT_TEMPLATE_UPDATED = "IMPORT_TEMPLATE_UPDATED";
    public static final String IMPORT_TEMPLATE_DELETED = "IMPORT_TEMPLATE_DELETED";
    public static final String EXPORT_REQUESTED = "EXPORT_REQUESTED";
    public static final String EXPORT_COMPLETED = "EXPORT_COMPLETED";
    public static final String EXPORT_DOWNLOADED = "EXPORT_DOWNLOADED";
    public static final String WATERMARK_TEMPLATE_CREATED = "WATERMARK_TEMPLATE_CREATED";
    public static final String WATERMARK_TEMPLATE_UPDATED = "WATERMARK_TEMPLATE_UPDATED";
    public static final String WATERMARK_TEMPLATE_DELETED = "WATERMARK_TEMPLATE_DELETED";
    public static final String WATERMARK_REQUESTED = "WATERMARK_REQUESTED";
    public static final String WATERMARK_COMPLETED = "WATERMARK_COMPLETED";
    public static final String WATERMARK_FAILED = "WATERMARK_FAILED";
    public static final String WATERMARK_RESULT_DOWNLOADED = "WATERMARK_RESULT_DOWNLOADED";

    public static final String DEPARTMENT_CREATED = "DEPARTMENT_CREATED";
    public static final String DEPARTMENT_UPDATED = "DEPARTMENT_UPDATED";
    public static final String DEPARTMENT_DELETED = "DEPARTMENT_DELETED";
    public static final String USER_DEPARTMENT_CHANGED = "USER_DEPARTMENT_CHANGED";
    public static final String WORKFLOW_TEMPLATE_CREATED = "WORKFLOW_TEMPLATE_CREATED";
    public static final String WORKFLOW_TEMPLATE_UPDATED = "WORKFLOW_TEMPLATE_UPDATED";
    public static final String WORKFLOW_TEMPLATE_STAGES_UPDATED = "WORKFLOW_TEMPLATE_STAGES_UPDATED";
    public static final String WORKFLOW_TEMPLATE_VERSION_CREATED = "WORKFLOW_TEMPLATE_VERSION_CREATED";
    public static final String WORKFLOW_TEMPLATE_ACTIVATED = "WORKFLOW_TEMPLATE_ACTIVATED";
    public static final String WORKFLOW_TEMPLATE_DEACTIVATED = "WORKFLOW_TEMPLATE_DEACTIVATED";
    public static final String WORKFLOW_TEMPLATE_DELETED = "WORKFLOW_TEMPLATE_DELETED";
    public static final String WORKFLOW_SUBMITTED = "WORKFLOW_SUBMITTED";
    public static final String WORKFLOW_TASK_CLAIMED = "WORKFLOW_TASK_CLAIMED";
    public static final String WORKFLOW_TASK_RELEASED = "WORKFLOW_TASK_RELEASED";
    public static final String WORKFLOW_TASK_APPROVED = "WORKFLOW_TASK_APPROVED";
    public static final String WORKFLOW_TASK_REJECTED = "WORKFLOW_TASK_REJECTED";
    public static final String WORKFLOW_STAGE_COMPLETED = "WORKFLOW_STAGE_COMPLETED";
    public static final String WORKFLOW_APPROVED = "WORKFLOW_APPROVED";
    public static final String WORKFLOW_REJECTED = "WORKFLOW_REJECTED";
    public static final String WORKFLOW_CANCELLED = "WORKFLOW_CANCELLED";
    public static final String WORKFLOW_DOCUMENT_VERSION_CHANGED = "WORKFLOW_DOCUMENT_VERSION_CHANGED";
    public static final String WORKFLOW_RESTARTED = "WORKFLOW_RESTARTED";
    public static final String WORKFLOW_TASK_DELEGATED = "WORKFLOW_TASK_DELEGATED";
    public static final String WORKFLOW_TASK_REASSIGNED = "WORKFLOW_TASK_REASSIGNED";
    public static final String WORKFLOW_TASK_REMINDER_SENT = "WORKFLOW_TASK_REMINDER_SENT";
    public static final String WORKFLOW_TASK_ESCALATED = "WORKFLOW_TASK_ESCALATED";
    public static final String WORKFLOW_INFO_REQUESTED = "WORKFLOW_INFO_REQUESTED";
    public static final String WORKFLOW_INFO_PROVIDED = "WORKFLOW_INFO_PROVIDED";
    public static final String WORKFLOW_INFO_DECLINED = "WORKFLOW_INFO_DECLINED";
    public static final String WORKFLOW_INFO_CANCELLED = "WORKFLOW_INFO_CANCELLED";
    public static final String WORKFLOW_INFO_REQUEST_REASSIGNED = "WORKFLOW_INFO_REQUEST_REASSIGNED";
    public static final String WORKFLOW_SUPPORTING_DOCUMENT_ADDED = "WORKFLOW_SUPPORTING_DOCUMENT_ADDED";
    public static final String WORKFLOW_SUPPORTING_DOCUMENT_REMOVED = "WORKFLOW_SUPPORTING_DOCUMENT_REMOVED";
    public static final String DOCUMENT_COMMENT_CREATED = "DOCUMENT_COMMENT_CREATED";
    public static final String DOCUMENT_COMMENT_UPDATED = "DOCUMENT_COMMENT_UPDATED";
    public static final String DOCUMENT_COMMENT_DELETED = "DOCUMENT_COMMENT_DELETED";


    private static final Map<String, AuditActionSpec> ACTIONS = Map.ofEntries(
            // Roles (RoleController) - module ASM
            Map.entry(ROLE_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(ROLE_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(ROLE_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Role-permission assignment (RolePermissionController) - module ASM
            Map.entry(ROLE_PERMISSION_ASSIGNED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(ROLE_PERMISSION_REVOKED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Permissions (PermissionController) - module ASM
            Map.entry(PERMISSION_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Auth (AuthService) - module ASM
            Map.entry(LOGIN_SUCCESS, new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            Map.entry(LOGIN_FAILURE, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(LOGOUT, new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            // No TOKEN_REFRESH entry - see AuthService#refresh's doc comment
            // for why silent session renewal is deliberately not audited.
            Map.entry(PASSWORD_RESET_REQUESTED, new AuditActionSpec("ASM", AuditSeverity.LOW, false)),
            Map.entry(PASSWORD_RESET_COMPLETED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PASSWORD_CHANGED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            // Impersonation (ImpersonationService) - module ASM: starting a
            // session under someone else's identity is at least as
            // sensitive as a role deletion (HIGH); ending one is routine
            // and expected, so MEDIUM matches USER_REACTIVATED's tier.
            Map.entry(IMPERSONATION_STARTED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),
            Map.entry(IMPERSONATION_ENDED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),

            // Users (UserController) - module OTUA
            Map.entry(USER_CREATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_UPDATED, new AuditActionSpec("OTUA", AuditSeverity.LOW, false)),
            Map.entry(USER_ROLE_ASSIGNED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_ROLE_REVOKED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_DEACTIVATED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, true)),
            Map.entry(USER_REACTIVATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(USER_DELETED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),

            // Permission actions (PermissionActionController) - module ASM,
            // same severities as the Permissions group above (this is one
            // level up the catalog hierarchy: Module -> Action -> Permission).
            Map.entry(PERMISSION_ACTION_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_ACTION_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_ACTION_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Permission modules (PermissionModuleController) - module ASM,
            // top of the same hierarchy.
            Map.entry(PERMISSION_MODULE_CREATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_MODULE_UPDATED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),
            Map.entry(PERMISSION_MODULE_DELETED, new AuditActionSpec("ASM", AuditSeverity.HIGH, false)),

            // Tenants (TenantController) - module OTUA (the permission
            // catalog's own "Manage Tenants" action group already lives
            // under OTUA, alongside user administration - see
            // V10__seed_fine_grained_permissions.sql). TENANT_PROVISIONED
            // is HIGH, not MEDIUM like a plain create - it also creates
            // that tenant's first TENANT_ADMIN account in the same call
            // (see TenantProvisioningService), so it's a privilege grant as
            // much as a resource creation. TENANT_DEACTIVATED has no reason
            // field to source reasonRequired=true from - DeleteMapping
            // takes no request body - same constraint documented above for
            // every other delete/deactivate entry in this catalog.
            Map.entry(TENANT_CREATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(TENANT_PROVISIONED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),
            Map.entry(TENANT_UPDATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(TENANT_DEACTIVATED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),

            // Every @PreAuthorize denial across the whole app, captured
            // centrally in GlobalExceptionHandler rather than per
            // endpoint - see that class's own comment. Module ASM (its
            // own doc comment above already scopes "security events" as
            // in-module). MEDIUM, not HIGH: a single denial is routinely
            // just a caller hitting a screen/action their role doesn't
            // grant (normal UI use, not inherently hostile) - severity
            // here is about "worth a human's attention," not "who was
            // right," so it's calibrated the same as LOGIN_FAILURE rather
            // than a destructive-action HIGH. No reason field exists at
            // this generic a layer, so reasonRequired stays false, same
            // constraint as every other entry here.
            Map.entry(ACCESS_DENIED, new AuditActionSpec("ASM", AuditSeverity.MEDIUM, false)),

            // Audit trail export (AuditEventController#exportCsv) - module ABDR
            Map.entry(AUDIT_LOG_EXPORTED, new AuditActionSpec("ABDR", AuditSeverity.MEDIUM, false)),

            // Background infrastructure (OutboxRelay) - module SHAD (System
            // Health and Administration Dashboard - the module whose whole
            // purpose is platform infrastructure status, not a business
            // domain). Only the terminal, unrecoverable failure is logged
            // here - see OutboxRelay's own doc comment for why every
            // successful publish and every merely-transient retry
            // deliberately are NOT audited (that would flood the trail with
            // routine, non-actionable noise).
            Map.entry(OUTBOX_EVENT_PUBLISH_FAILED, new AuditActionSpec("SHAD", AuditSeverity.HIGH, false)),

            // Document types (DocumentTypeController) - module MDTM. Same
            // severity convention as every other catalog-entity group above:
            // create/update/activate are MEDIUM, delete-strength actions are
            // HIGH. There's no DOCUMENT_TYPE_DELETED - DocumentTypeService
            // has no delete endpoint, only activate/deactivate (see its own
            // doc comment); deactivating blocks new documents of that type,
            // which is why it's HIGH rather than MEDIUM like a plain update.
            Map.entry(DOCUMENT_TYPE_CREATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_TYPE_UPDATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_TYPE_ACTIVATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_TYPE_DEACTIVATED, new AuditActionSpec("MDTM", AuditSeverity.HIGH, false)),

            // Publishing a new field-mapping schema version for a document
            // type (DocumentTypeFieldController) - module MDTM. HIGH: this
            // is the single action that changes what fields exist, and
            // which are mandatory/hidden, for every future document of that
            // type - comparable in blast radius to TENANT_PROVISIONED.
            Map.entry(DOCUMENT_TYPE_SCHEMA_PUBLISHED, new AuditActionSpec("MDTM", AuditSeverity.HIGH, false)),

            // Metadata field catalog (MetadataFieldController) - module MDTM.
            // Deprecate/delete are HIGH: both end a field's usability
            // (deprecate blocks new mappings, delete removes it outright -
            // see MetadataFieldServiceImpl's own doc comment on why these
            // are two distinct actions rather than one).
            Map.entry(METADATA_FIELD_CREATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(METADATA_FIELD_UPDATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(METADATA_FIELD_ACTIVATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(METADATA_FIELD_DEACTIVATED, new AuditActionSpec("MDTM", AuditSeverity.HIGH, false)),
            Map.entry(METADATA_FIELD_DEPRECATED, new AuditActionSpec("MDTM", AuditSeverity.HIGH, false)),
            Map.entry(METADATA_FIELD_DELETED, new AuditActionSpec("MDTM", AuditSeverity.HIGH, false)),

            // Metadata field options (MetadataFieldOptionController) - module
            // MDTM, one level below a field in the catalog hierarchy - same
            // create/update/delete severity convention as Permissions/
            // PermissionActions above.
            Map.entry(METADATA_FIELD_OPTION_CREATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(METADATA_FIELD_OPTION_UPDATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(METADATA_FIELD_OPTION_DELETED, new AuditActionSpec("MDTM", AuditSeverity.HIGH, false)),

            // Metadata field validation rules (MetadataFieldValidationController)
            // - module MDTM. Deleting a rule loosens a data-integrity
            // constraint for every future document of that type, same
            // reasoning as ROLE_PERMISSION_REVOKED being HIGH.
            Map.entry(METADATA_FIELD_VALIDATION_CREATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(METADATA_FIELD_VALIDATION_UPDATED, new AuditActionSpec("MDTM", AuditSeverity.MEDIUM, false)),
            Map.entry(METADATA_FIELD_VALIDATION_DELETED, new AuditActionSpec("MDTM", AuditSeverity.HIGH, false)),

            // Documents (DocumentController) - module DOCM. DOWNLOADED is
            // LOW, not MEDIUM like a plain create - it's a routine, high-
            // frequency read, same severity tier as LOGIN_SUCCESS, not a
            // state-changing action.
            Map.entry(DOCUMENT_UPLOADED, new AuditActionSpec("DOCM", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_DOWNLOADED, new AuditActionSpec("DOCM", AuditSeverity.LOW, false)),
            // Opened in the in-app preview rather than saved as a file.
            Map.entry(DOCUMENT_VIEWED, new AuditActionSpec("DOCM", AuditSeverity.LOW, false)),
            Map.entry(DOCUMENT_DELETED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, false)),

            // Storage locations (StorageLocationController) - also module
            // DOCM: registering a new object-storage backend for a tenant
            // is tenant infrastructure, comparable in blast radius to
            // TENANT_PROVISIONED, so HIGH rather than the MEDIUM a plain
            // catalog-entity create would get.
            Map.entry(STORAGE_LOCATION_CREATED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, false)),

            // Malware scan outcomes (com.oak.dms.scanning ingestion gate) -
            // module DOCM, VMSBDI-NFR-003. INFECTED/QUARANTINED are HIGH: a
            // detected threat is a security event. FAILED is logged per
            // failed attempt (retries are capped), UNSCANNABLE for encrypted
            // or password-protected uploads. Quarantine review actions
            // (release/delete) are Phase 2.
            Map.entry(DOCUMENT_SCAN_CLEAN, new AuditActionSpec("DOCM", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_SCAN_INFECTED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, false)),
            Map.entry(DOCUMENT_SCAN_FAILED, new AuditActionSpec("DOCM", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_SCAN_UNSCANNABLE, new AuditActionSpec("DOCM", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_QUARANTINED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, false)),

            // Quarantine review actions (QuarantineController) - module DOCM,
            // VMSBDI FR-005 / NFR-003. All HIGH: each is a decision about a
            // security control. All reason-required - every one of these
            // endpoints takes a mandatory reason (QuarantineActionRequest).
            // RELEASED: a re-scan came back clean and the file was promoted.
            // RELEASE_REFUSED: a re-scan still detected the threat.
            // OVERRIDE_RELEASED: an administrator force-released a file the
            // scanner still flags (docm.quarantine.override).
            Map.entry(QUARANTINE_RELEASED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, true)),
            Map.entry(QUARANTINE_RELEASE_REFUSED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, true)),
            Map.entry(QUARANTINE_OVERRIDE_RELEASED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, true)),
            Map.entry(QUARANTINE_DELETED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, true)),

            // Bulk import (BulkImportAuditor) - module FIE.
            // One event per batch, not per row: each created document is
            // already audited as DOCUMENT_UPLOADED by the scan gate.
            Map.entry(BULK_IMPORT_STARTED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),
            Map.entry(BULK_IMPORT_COMPLETED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),
            Map.entry(BULK_IMPORT_CANCELLED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),

            // A file refused at intake by the virus scan - a security event, HIGH.
            Map.entry(IMPORT_FILE_BLOCKED, new AuditActionSpec("FIE", AuditSeverity.HIGH, false)),

            // Folders (FolderService) - module DSC; moving documents - DOCM.
            Map.entry(FOLDER_CREATED, new AuditActionSpec("DSC", AuditSeverity.MEDIUM, false)),
            Map.entry(FOLDER_RENAMED, new AuditActionSpec("DSC", AuditSeverity.MEDIUM, false)),
            Map.entry(FOLDER_MOVED, new AuditActionSpec("DSC", AuditSeverity.MEDIUM, false)),
            Map.entry(FOLDER_DELETED, new AuditActionSpec("DSC", AuditSeverity.MEDIUM, false)),
            // Who can get into a folder changed - security relevant, so HIGH.
            Map.entry(FOLDER_ACCESS_CHANGED, new AuditActionSpec("DSC", AuditSeverity.HIGH, false)),
            Map.entry(DOCUMENT_ACCESS_CHANGED, new AuditActionSpec("DOCM", AuditSeverity.HIGH, false)),

            // Encryption at rest (EncryptionAdminService) - module DE (DE-NFR-006). Every unwrap is
            // additionally an authenticated request in OpenBao's own audit log.
            Map.entry(ENCRYPTION_KEY_ROTATED, new AuditActionSpec("DE", AuditSeverity.HIGH, false)),
            Map.entry(ENCRYPTION_KEYS_REWRAPPED, new AuditActionSpec("DE", AuditSeverity.MEDIUM, false)),
            Map.entry(ENCRYPTION_BACKFILL_COMPLETED, new AuditActionSpec("DE", AuditSeverity.MEDIUM, false)),
            Map.entry(ENCRYPTION_ROTATION_POLICY_CHANGED, new AuditActionSpec("DE", AuditSeverity.MEDIUM, false)),
            // The schedule's reminder that a key is due for rotation (not auto-rotated) - system actor.
            Map.entry(ENCRYPTION_KEY_ROTATION_DUE, new AuditActionSpec("DE", AuditSeverity.MEDIUM, false)),
            // Crypto-shredding a removed tenant's keys: irreversible, so a reason is required.
            Map.entry(ENCRYPTION_TENANT_KEYS_SHREDDED, new AuditActionSpec("DE", AuditSeverity.HIGH, true)),
            Map.entry(DOCUMENT_MOVED, new AuditActionSpec("DOCM", AuditSeverity.MEDIUM, false)),
            // Version history (DocumentVersionService) - module DVC; a new version itself is DOCUMENT_UPLOADED.
            Map.entry(DOCUMENT_VERSION_RESTORED, new AuditActionSpec("DVC", AuditSeverity.MEDIUM, false)),
            // The recycle bin (RecycleBinService) - module RBR. A purge destroys the files for good.
            Map.entry(DOCUMENT_RESTORED, new AuditActionSpec("RBR", AuditSeverity.MEDIUM, false)),
            Map.entry(DOCUMENT_PURGED, new AuditActionSpec("RBR", AuditSeverity.HIGH, false)),
            Map.entry(RECYCLE_BIN_RETENTION_CHANGED, new AuditActionSpec("RBR", AuditSeverity.MEDIUM, false)),
            Map.entry(PIN_LIMIT_CHANGED, new AuditActionSpec("PID", AuditSeverity.LOW, false)),

            // Saved import templates (ImportTemplateService) - module FIE.
            Map.entry(IMPORT_TEMPLATE_CREATED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),
            Map.entry(IMPORT_TEMPLATE_UPDATED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),
            Map.entry(IMPORT_TEMPLATE_DELETED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),

            // Bulk export (ExportJobService / ExportJobWorker) - module FIE.
            // DOWNLOADED is HIGH: it is the moment a bulk copy of documents
            // leaves the system. Each document read into the ZIP is also a
            // DOCUMENT_DOWNLOADED, with {"via": "bulk_export"}.
            Map.entry(EXPORT_REQUESTED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),
            Map.entry(EXPORT_COMPLETED, new AuditActionSpec("FIE", AuditSeverity.MEDIUM, false)),
            Map.entry(EXPORT_DOWNLOADED, new AuditActionSpec("FIE", AuditSeverity.HIGH, false)),

            // Watermarking (WatermarkTemplateService / WatermarkService /
            // WatermarkJobWorker) - module DW, the module code V7 seeded for
            // it. Template changes are MEDIUM: they decide what every future
            // copy will say, but affect no copy already produced.
            Map.entry(WATERMARK_TEMPLATE_CREATED, new AuditActionSpec("DW", AuditSeverity.MEDIUM, false)),
            Map.entry(WATERMARK_TEMPLATE_UPDATED, new AuditActionSpec("DW", AuditSeverity.MEDIUM, false)),
            Map.entry(WATERMARK_TEMPLATE_DELETED, new AuditActionSpec("DW", AuditSeverity.MEDIUM, false)),
            // REQUESTED carries the resolved text and is written in the
            // request thread, which is the only place impersonation is
            // captured - the worker runs as the effective user with no
            // "acting as" of its own, so the two later events reference the
            // job id and join back to this one.
            Map.entry(WATERMARK_REQUESTED, new AuditActionSpec("DW", AuditSeverity.MEDIUM, false)),
            Map.entry(WATERMARK_COMPLETED, new AuditActionSpec("DW", AuditSeverity.MEDIUM, false)),
            Map.entry(WATERMARK_FAILED, new AuditActionSpec("DW", AuditSeverity.LOW, false)),
            // HIGH for the same reason as EXPORT_DOWNLOADED: this is the
            // moment a copy of a document leaves the system - one carrying
            // the name of the person who took it.
            Map.entry(WATERMARK_RESULT_DOWNLOADED, new AuditActionSpec("DW", AuditSeverity.HIGH, false)),

            // Departments (DepartmentController) - module OTUA. They define
            // who sits in a department approver pool, hence MEDIUM.
            Map.entry(DEPARTMENT_CREATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(DEPARTMENT_UPDATED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),
            Map.entry(DEPARTMENT_DELETED, new AuditActionSpec("OTUA", AuditSeverity.HIGH, false)),
            Map.entry(USER_DEPARTMENT_CHANGED, new AuditActionSpec("OTUA", AuditSeverity.MEDIUM, false)),

            // Workflow Automation - module WA (3.1, docs/events/workflow-audit-events.md).
            // Configuration changes: who approves what.
            Map.entry(WORKFLOW_TEMPLATE_CREATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TEMPLATE_UPDATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TEMPLATE_STAGES_UPDATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TEMPLATE_VERSION_CREATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TEMPLATE_ACTIVATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TEMPLATE_DEACTIVATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TEMPLATE_DELETED, new AuditActionSpec("WA", AuditSeverity.HIGH, false)),
            // Runtime. A task rejection, a workflow rejection and a cancel
            // always carry a reason (WA-FR-003, WA-FR-014).
            Map.entry(WORKFLOW_SUBMITTED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TASK_CLAIMED, new AuditActionSpec("WA", AuditSeverity.LOW, false)),
            Map.entry(WORKFLOW_TASK_RELEASED, new AuditActionSpec("WA", AuditSeverity.LOW, false)),
            Map.entry(WORKFLOW_TASK_APPROVED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TASK_REJECTED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, true)),
            Map.entry(WORKFLOW_STAGE_COMPLETED, new AuditActionSpec("WA", AuditSeverity.LOW, false)),
            Map.entry(WORKFLOW_APPROVED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_REJECTED, new AuditActionSpec("WA", AuditSeverity.HIGH, true)),
            Map.entry(WORKFLOW_CANCELLED, new AuditActionSpec("WA", AuditSeverity.HIGH, true)),
            Map.entry(WORKFLOW_DOCUMENT_VERSION_CHANGED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_RESTARTED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            // Phase 2 (docs/workflow-phase2-plan.md S10). A reassignment by an
            // admin, a declined request and a cancelled request carry a reason.
            Map.entry(WORKFLOW_TASK_DELEGATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_TASK_REASSIGNED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, true)),
            Map.entry(WORKFLOW_TASK_REMINDER_SENT, new AuditActionSpec("WA", AuditSeverity.LOW, false)),
            Map.entry(WORKFLOW_TASK_ESCALATED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_INFO_REQUESTED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_INFO_PROVIDED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, false)),
            Map.entry(WORKFLOW_INFO_DECLINED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, true)),
            Map.entry(WORKFLOW_INFO_CANCELLED, new AuditActionSpec("WA", AuditSeverity.MEDIUM, true)),
            Map.entry(WORKFLOW_INFO_REQUEST_REASSIGNED, new AuditActionSpec("WA", AuditSeverity.LOW, false)),
            Map.entry(WORKFLOW_SUPPORTING_DOCUMENT_ADDED, new AuditActionSpec("WA", AuditSeverity.LOW, false)),
            Map.entry(WORKFLOW_SUPPORTING_DOCUMENT_REMOVED, new AuditActionSpec("WA", AuditSeverity.LOW, false)),
            // Document comments - module DOCM. Rows carry ids and length, never the body.
            Map.entry(DOCUMENT_COMMENT_CREATED, new AuditActionSpec("DOCM", AuditSeverity.LOW, false)),
            Map.entry(DOCUMENT_COMMENT_UPDATED, new AuditActionSpec("DOCM", AuditSeverity.LOW, false)),
            Map.entry(DOCUMENT_COMMENT_DELETED, new AuditActionSpec("DOCM", AuditSeverity.MEDIUM, false))
    );

    private AuditActionCatalog() {
    }

    public static AuditActionSpec require(String action) {
        AuditActionSpec spec = ACTIONS.get(action);
        if (spec == null) {
            throw new IllegalStateException(
                    "Action '" + action + "' is not registered in AuditActionCatalog - add it there "
                            + "(module, severity, reason-required) rather than passing those ad hoc.");
        }
        return spec;
    }
}
