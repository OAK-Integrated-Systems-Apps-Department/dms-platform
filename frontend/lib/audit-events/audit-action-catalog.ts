/**
 * Mirrors backend/dms-core's AuditActionCatalog.java - keep both in sync.
 * There's no fetch endpoint for this catalog (it's a fixed, compiled-in
 * registry on the backend, not a DB-backed one like permission_modules/
 * permission_actions), so the audit-log page's module→action filter
 * cascade needs its own copy here, the same way permission-codes.ts
 * mirrors PermissionCodes.java.
 *
 * Module codes match permission_modules.module_code (ASM/OTUA/ABDR), but
 * the action list itself is a completely different catalog than
 * permission_actions - do not reuse `usePermissionsStore`'s module/action
 * data for this filter, it answers a different question ("what can be
 * granted") than this one ("what audit events exist").
 */
export interface AuditActionCatalogEntry {
  action: string
  module: string
  label: string
}

export const AUDIT_ACTION_CATALOG: AuditActionCatalogEntry[] = [
  { action: "ROLE_CREATED", module: "ASM", label: "Role created" },
  { action: "ROLE_UPDATED", module: "ASM", label: "Role updated" },
  { action: "ROLE_DELETED", module: "ASM", label: "Role deleted" },
  {
    action: "ROLE_PERMISSION_ASSIGNED",
    module: "ASM",
    label: "Permission assigned to role",
  },
  {
    action: "ROLE_PERMISSION_REVOKED",
    module: "ASM",
    label: "Permission revoked from role",
  },
  { action: "PERMISSION_CREATED", module: "ASM", label: "Permission created" },
  { action: "PERMISSION_UPDATED", module: "ASM", label: "Permission updated" },
  { action: "PERMISSION_DELETED", module: "ASM", label: "Permission deleted" },
  { action: "LOGIN_SUCCESS", module: "ASM", label: "Login succeeded" },
  { action: "LOGIN_FAILURE", module: "ASM", label: "Login failed" },
  { action: "LOGOUT", module: "ASM", label: "Logout" },
  {
    action: "PASSWORD_RESET_REQUESTED",
    module: "ASM",
    label: "Password reset requested",
  },
  {
    action: "PASSWORD_RESET_COMPLETED",
    module: "ASM",
    label: "Password reset completed",
  },
  // The holder changing their own password from inside the DMS - distinct
  // from a reset via an emailed link, which is what the entry above covers.
  { action: "PASSWORD_CHANGED", module: "ASM", label: "Password changed" },
  {
    action: "IMPERSONATION_STARTED",
    module: "ASM",
    label: "Impersonation started",
  },
  {
    action: "IMPERSONATION_ENDED",
    module: "ASM",
    label: "Impersonation ended",
  },
  { action: "USER_CREATED", module: "OTUA", label: "User created" },
  { action: "USER_UPDATED", module: "OTUA", label: "User updated" },
  {
    action: "USER_ROLE_ASSIGNED",
    module: "OTUA",
    label: "Role assigned to user",
  },
  {
    action: "USER_ROLE_REVOKED",
    module: "OTUA",
    label: "Role revoked from user",
  },
  { action: "USER_DEACTIVATED", module: "OTUA", label: "User deactivated" },
  { action: "USER_REACTIVATED", module: "OTUA", label: "User reactivated" },
  { action: "USER_DELETED", module: "OTUA", label: "User deleted" },
  {
    action: "PERMISSION_ACTION_CREATED",
    module: "ASM",
    label: "Permission action created",
  },
  {
    action: "PERMISSION_ACTION_UPDATED",
    module: "ASM",
    label: "Permission action updated",
  },
  {
    action: "PERMISSION_ACTION_DELETED",
    module: "ASM",
    label: "Permission action deleted",
  },
  {
    action: "PERMISSION_MODULE_CREATED",
    module: "ASM",
    label: "Permission module created",
  },
  {
    action: "PERMISSION_MODULE_UPDATED",
    module: "ASM",
    label: "Permission module updated",
  },
  {
    action: "PERMISSION_MODULE_DELETED",
    module: "ASM",
    label: "Permission module deleted",
  },
  { action: "TENANT_CREATED", module: "OTUA", label: "Tenant created" },
  { action: "TENANT_PROVISIONED", module: "OTUA", label: "Tenant provisioned" },
  { action: "TENANT_UPDATED", module: "OTUA", label: "Tenant updated" },
  { action: "TENANT_DEACTIVATED", module: "OTUA", label: "Tenant deactivated" },
  { action: "ACCESS_DENIED", module: "ASM", label: "Access denied" },
  { action: "AUDIT_LOG_EXPORTED", module: "ABDR", label: "Audit log exported" },
  {
    action: "OUTBOX_EVENT_PUBLISH_FAILED",
    module: "SHAD",
    label: "Outbox event publish failed",
  },
  {
    action: "ENCRYPTION_KEY_ROTATED",
    module: "DE",
    label: "Encryption key rotated",
  },
  {
    action: "ENCRYPTION_KEYS_REWRAPPED",
    module: "DE",
    label: "Encryption keys re-wrapped",
  },
  {
    action: "ENCRYPTION_BACKFILL_COMPLETED",
    module: "DE",
    label: "Existing documents encrypted",
  },
  {
    action: "ENCRYPTION_ROTATION_POLICY_CHANGED",
    module: "DE",
    label: "Key rotation schedule changed",
  },
  {
    action: "ENCRYPTION_KEY_ROTATION_DUE",
    module: "DE",
    label: "Key rotation due",
  },
  {
    action: "ENCRYPTION_TENANT_KEYS_SHREDDED",
    module: "DE",
    label: "Organisation keys destroyed",
  },
  {
    action: "PIN_LIMIT_CHANGED",
    module: "PID",
    label: "Pin limit changed",
  },
  {
    action: "DOCUMENT_TYPE_CREATED",
    module: "MDTM",
    label: "Document type created",
  },
  {
    action: "DOCUMENT_TYPE_UPDATED",
    module: "MDTM",
    label: "Document type updated",
  },
  {
    action: "DOCUMENT_TYPE_ACTIVATED",
    module: "MDTM",
    label: "Document type activated",
  },
  {
    action: "DOCUMENT_TYPE_DEACTIVATED",
    module: "MDTM",
    label: "Document type deactivated",
  },
  {
    action: "DOCUMENT_TYPE_SCHEMA_PUBLISHED",
    module: "MDTM",
    label: "Document type schema published",
  },
  {
    action: "METADATA_FIELD_CREATED",
    module: "MDTM",
    label: "Metadata field created",
  },
  {
    action: "METADATA_FIELD_UPDATED",
    module: "MDTM",
    label: "Metadata field updated",
  },
  {
    action: "METADATA_FIELD_ACTIVATED",
    module: "MDTM",
    label: "Metadata field activated",
  },
  {
    action: "METADATA_FIELD_DEACTIVATED",
    module: "MDTM",
    label: "Metadata field deactivated",
  },
  {
    action: "METADATA_FIELD_DEPRECATED",
    module: "MDTM",
    label: "Metadata field deprecated",
  },
  {
    action: "METADATA_FIELD_DELETED",
    module: "MDTM",
    label: "Metadata field deleted",
  },
  {
    action: "METADATA_FIELD_OPTION_CREATED",
    module: "MDTM",
    label: "Metadata field option created",
  },
  {
    action: "METADATA_FIELD_OPTION_UPDATED",
    module: "MDTM",
    label: "Metadata field option updated",
  },
  {
    action: "METADATA_FIELD_OPTION_DELETED",
    module: "MDTM",
    label: "Metadata field option deleted",
  },
  {
    action: "METADATA_FIELD_VALIDATION_CREATED",
    module: "MDTM",
    label: "Metadata field validation rule created",
  },
  {
    action: "METADATA_FIELD_VALIDATION_UPDATED",
    module: "MDTM",
    label: "Metadata field validation rule updated",
  },
  {
    action: "METADATA_FIELD_VALIDATION_DELETED",
    module: "MDTM",
    label: "Metadata field validation rule deleted",
  },
  {
    action: "DOCUMENT_ACCESS_CHANGED",
    module: "DOCM",
    label: "Document access changed",
  },
  {
    action: "DOCUMENT_COMMENT_CREATED",
    module: "DOCM",
    label: "Document comment created",
  },
  {
    action: "DOCUMENT_COMMENT_DELETED",
    module: "DOCM",
    label: "Document comment deleted",
  },
  {
    action: "DOCUMENT_COMMENT_UPDATED",
    module: "DOCM",
    label: "Document comment updated",
  },
  {
    action: "DOCUMENT_DELETED",
    module: "DOCM",
    label: "Document deleted",
  },
  {
    action: "DOCUMENT_DOWNLOADED",
    module: "DOCM",
    label: "Document downloaded",
  },
  {
    action: "DOCUMENT_MOVED",
    module: "DOCM",
    label: "Document moved",
  },
  {
    action: "DOCUMENT_QUARANTINED",
    module: "DOCM",
    label: "Document quarantined",
  },
  {
    action: "DOCUMENT_SCAN_CLEAN",
    module: "DOCM",
    label: "Document scan clean",
  },
  {
    action: "DOCUMENT_SCAN_FAILED",
    module: "DOCM",
    label: "Document scan failed",
  },
  {
    action: "DOCUMENT_SCAN_INFECTED",
    module: "DOCM",
    label: "Document scan infected",
  },
  {
    action: "DOCUMENT_SCAN_UNSCANNABLE",
    module: "DOCM",
    label: "Document scan unscannable",
  },
  {
    action: "DOCUMENT_UPLOADED",
    module: "DOCM",
    label: "Document uploaded",
  },
  {
    action: "DOCUMENT_VIEWED",
    module: "DOCM",
    label: "Document viewed",
  },
  {
    action: "QUARANTINE_DELETED",
    module: "DOCM",
    label: "Quarantine deleted",
  },
  {
    action: "QUARANTINE_OVERRIDE_RELEASED",
    module: "DOCM",
    label: "Quarantine override released",
  },
  {
    action: "QUARANTINE_RELEASED",
    module: "DOCM",
    label: "Quarantine released",
  },
  {
    action: "QUARANTINE_RELEASE_REFUSED",
    module: "DOCM",
    label: "Quarantine release refused",
  },
  {
    action: "STORAGE_LOCATION_CREATED",
    module: "DOCM",
    label: "Storage location created",
  },
  {
    action: "FOLDER_ACCESS_CHANGED",
    module: "DSC",
    label: "Folder access changed",
  },
  {
    action: "FOLDER_CREATED",
    module: "DSC",
    label: "Folder created",
  },
  {
    action: "FOLDER_DELETED",
    module: "DSC",
    label: "Folder deleted",
  },
  {
    action: "FOLDER_MOVED",
    module: "DSC",
    label: "Folder moved",
  },
  {
    action: "FOLDER_RENAMED",
    module: "DSC",
    label: "Folder renamed",
  },
  {
    action: "DOCUMENT_VERSION_RESTORED",
    module: "DVC",
    label: "Document version restored",
  },
  {
    action: "BULK_IMPORT_CANCELLED",
    module: "FIE",
    label: "Bulk import cancelled",
  },
  {
    action: "BULK_IMPORT_COMPLETED",
    module: "FIE",
    label: "Bulk import completed",
  },
  {
    action: "BULK_IMPORT_STARTED",
    module: "FIE",
    label: "Bulk import started",
  },
  {
    action: "EXPORT_COMPLETED",
    module: "FIE",
    label: "Export completed",
  },
  {
    action: "EXPORT_DOWNLOADED",
    module: "FIE",
    label: "Export downloaded",
  },
  {
    action: "EXPORT_REQUESTED",
    module: "FIE",
    label: "Export requested",
  },
  {
    action: "IMPORT_FILE_BLOCKED",
    module: "FIE",
    label: "Import file blocked",
  },
  {
    action: "IMPORT_TEMPLATE_CREATED",
    module: "FIE",
    label: "Import template created",
  },
  {
    action: "IMPORT_TEMPLATE_DELETED",
    module: "FIE",
    label: "Import template deleted",
  },
  {
    action: "IMPORT_TEMPLATE_UPDATED",
    module: "FIE",
    label: "Import template updated",
  },
  {
    action: "DEPARTMENT_CREATED",
    module: "OTUA",
    label: "Department created",
  },
  {
    action: "DEPARTMENT_DELETED",
    module: "OTUA",
    label: "Department deleted",
  },
  {
    action: "DEPARTMENT_UPDATED",
    module: "OTUA",
    label: "Department updated",
  },
  {
    action: "USER_DEPARTMENT_CHANGED",
    module: "OTUA",
    label: "User department changed",
  },
  {
    action: "DOCUMENT_PURGED",
    module: "RBR",
    label: "Document purged",
  },
  {
    action: "DOCUMENT_RESTORED",
    module: "RBR",
    label: "Document restored",
  },
  {
    action: "RECYCLE_BIN_RETENTION_CHANGED",
    module: "RBR",
    label: "Recycle bin retention changed",
  },
  {
    action: "WORKFLOW_APPROVED",
    module: "WA",
    label: "Workflow approved",
  },
  {
    action: "WORKFLOW_CANCELLED",
    module: "WA",
    label: "Workflow cancelled",
  },
  {
    action: "WORKFLOW_DOCUMENT_VERSION_CHANGED",
    module: "WA",
    label: "Workflow document version changed",
  },
  {
    action: "WORKFLOW_INFO_CANCELLED",
    module: "WA",
    label: "Workflow info cancelled",
  },
  {
    action: "WORKFLOW_INFO_DECLINED",
    module: "WA",
    label: "Workflow info declined",
  },
  {
    action: "WORKFLOW_INFO_PROVIDED",
    module: "WA",
    label: "Workflow info provided",
  },
  {
    action: "WORKFLOW_INFO_REQUESTED",
    module: "WA",
    label: "Workflow info requested",
  },
  {
    action: "WORKFLOW_INFO_REQUEST_REASSIGNED",
    module: "WA",
    label: "Workflow info request reassigned",
  },
  {
    action: "WORKFLOW_REJECTED",
    module: "WA",
    label: "Workflow rejected",
  },
  {
    action: "WORKFLOW_RESTARTED",
    module: "WA",
    label: "Workflow restarted",
  },
  {
    action: "WORKFLOW_STAGE_COMPLETED",
    module: "WA",
    label: "Workflow stage completed",
  },
  {
    action: "WORKFLOW_SUBMITTED",
    module: "WA",
    label: "Workflow submitted",
  },
  {
    action: "WORKFLOW_SUPPORTING_DOCUMENT_ADDED",
    module: "WA",
    label: "Workflow supporting document added",
  },
  {
    action: "WORKFLOW_SUPPORTING_DOCUMENT_REMOVED",
    module: "WA",
    label: "Workflow supporting document removed",
  },
  {
    action: "WORKFLOW_TASK_APPROVED",
    module: "WA",
    label: "Workflow task approved",
  },
  {
    action: "WORKFLOW_TASK_CLAIMED",
    module: "WA",
    label: "Workflow task claimed",
  },
  {
    action: "WORKFLOW_TASK_DELEGATED",
    module: "WA",
    label: "Workflow task delegated",
  },
  {
    action: "WORKFLOW_TASK_ESCALATED",
    module: "WA",
    label: "Workflow task escalated",
  },
  {
    action: "WORKFLOW_TASK_REASSIGNED",
    module: "WA",
    label: "Workflow task reassigned",
  },
  {
    action: "WORKFLOW_TASK_REJECTED",
    module: "WA",
    label: "Workflow task rejected",
  },
  {
    action: "WORKFLOW_TASK_RELEASED",
    module: "WA",
    label: "Workflow task released",
  },
  {
    action: "WORKFLOW_TASK_REMINDER_SENT",
    module: "WA",
    label: "Workflow task reminder sent",
  },
  {
    action: "WORKFLOW_TEMPLATE_ACTIVATED",
    module: "WA",
    label: "Workflow template activated",
  },
  {
    action: "WORKFLOW_TEMPLATE_CREATED",
    module: "WA",
    label: "Workflow template created",
  },
  {
    action: "WORKFLOW_TEMPLATE_DEACTIVATED",
    module: "WA",
    label: "Workflow template deactivated",
  },
  {
    action: "WORKFLOW_TEMPLATE_DELETED",
    module: "WA",
    label: "Workflow template deleted",
  },
  {
    action: "WORKFLOW_TEMPLATE_STAGES_UPDATED",
    module: "WA",
    label: "Workflow template stages updated",
  },
  {
    action: "WORKFLOW_TEMPLATE_UPDATED",
    module: "WA",
    label: "Workflow template updated",
  },
  {
    action: "WORKFLOW_TEMPLATE_VERSION_CREATED",
    module: "WA",
    label: "Workflow template version created",
  },
  {
    action: "RELATIONSHIP_CREATED",
    module: "DRM",
    label: "Document relationship created",
  },
  {
    action: "RELATIONSHIP_UPDATED",
    module: "DRM",
    label: "Document relationship updated",
  },
  {
    action: "RELATIONSHIP_REMOVED",
    module: "DRM",
    label: "Document relationship removed",
  },
  {
    action: "RELATIONSHIP_BATCH_CREATED",
    module: "DRM",
    label: "Document relationships created in bulk",
  },
  {
    action: "RELATIONSHIP_REMOVED_TARGET_PURGED",
    module: "DRM",
    label: "Document relationship removed (document purged)",
  },
  {
    action: "RELATIONSHIP_TYPE_CREATED",
    module: "DRM",
    label: "Relationship type created",
  },
  {
    action: "RELATIONSHIP_TYPE_UPDATED",
    module: "DRM",
    label: "Relationship type updated",
  },
  {
    action: "RELATIONSHIP_TYPE_ENABLED",
    module: "DRM",
    label: "Relationship type enabled",
  },
  {
    action: "RELATIONSHIP_TYPE_DISABLED",
    module: "DRM",
    label: "Relationship type disabled",
  },
  {
    action: "RELATIONSHIP_TYPE_DELETED",
    module: "DRM",
    label: "Relationship type deleted",
  },
]

export const AUDIT_MODULE_LABELS: Record<string, string> = {
  ASM: "Access and Security Management",
  OTUA: "Organization, Tenant and User Administration",
  ABDR: "Comprehensive Audit Management",
  SHAD: "System Health and Administration Dashboard",
  MDTM: "Metadata and Document-Type Management",
  DE: "Document Encryption",
  PID: "Pin Important Documents",
  DOCM: "Document Management",
  FIE: "File Import/Export",
  RBR: "Recycle Bin and Recovery",
  DVC: "Document Version Control",
  DSC: "Document Storage & Centralization",
  WA: "Workflow Automation",
  DRM: "Document Relationships Management",
}

export function auditModuleOptions(): string[] {
  return Array.from(new Set(AUDIT_ACTION_CATALOG.map((entry) => entry.module)))
}

/** Every module's actions if `modules` is empty - the cascade's "no module selected yet" state. */
export function auditActionOptionsForModules(
  modules: string[]
): AuditActionCatalogEntry[] {
  return modules.length === 0
    ? AUDIT_ACTION_CATALOG
    : AUDIT_ACTION_CATALOG.filter((entry) => modules.includes(entry.module))
}
