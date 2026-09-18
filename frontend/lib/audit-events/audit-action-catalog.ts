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
  { action: "ROLE_PERMISSION_ASSIGNED", module: "ASM", label: "Permission assigned to role" },
  { action: "ROLE_PERMISSION_REVOKED", module: "ASM", label: "Permission revoked from role" },
  { action: "PERMISSION_CREATED", module: "ASM", label: "Permission created" },
  { action: "PERMISSION_UPDATED", module: "ASM", label: "Permission updated" },
  { action: "PERMISSION_DELETED", module: "ASM", label: "Permission deleted" },
  { action: "LOGIN_SUCCESS", module: "ASM", label: "Login succeeded" },
  { action: "LOGIN_FAILURE", module: "ASM", label: "Login failed" },
  { action: "LOGOUT", module: "ASM", label: "Logout" },
  { action: "PASSWORD_RESET_REQUESTED", module: "ASM", label: "Password reset requested" },
  { action: "PASSWORD_RESET_COMPLETED", module: "ASM", label: "Password reset completed" },
  { action: "IMPERSONATION_STARTED", module: "ASM", label: "Impersonation started" },
  { action: "IMPERSONATION_ENDED", module: "ASM", label: "Impersonation ended" },
  { action: "USER_CREATED", module: "OTUA", label: "User created" },
  { action: "USER_UPDATED", module: "OTUA", label: "User updated" },
  { action: "USER_ROLE_ASSIGNED", module: "OTUA", label: "Role assigned to user" },
  { action: "USER_ROLE_REVOKED", module: "OTUA", label: "Role revoked from user" },
  { action: "USER_DEACTIVATED", module: "OTUA", label: "User deactivated" },
  { action: "USER_REACTIVATED", module: "OTUA", label: "User reactivated" },
  { action: "USER_DELETED", module: "OTUA", label: "User deleted" },
  { action: "PERMISSION_ACTION_CREATED", module: "ASM", label: "Permission action created" },
  { action: "PERMISSION_ACTION_UPDATED", module: "ASM", label: "Permission action updated" },
  { action: "PERMISSION_ACTION_DELETED", module: "ASM", label: "Permission action deleted" },
  { action: "PERMISSION_MODULE_CREATED", module: "ASM", label: "Permission module created" },
  { action: "PERMISSION_MODULE_UPDATED", module: "ASM", label: "Permission module updated" },
  { action: "PERMISSION_MODULE_DELETED", module: "ASM", label: "Permission module deleted" },
  { action: "TENANT_CREATED", module: "OTUA", label: "Tenant created" },
  { action: "TENANT_PROVISIONED", module: "OTUA", label: "Tenant provisioned" },
  { action: "TENANT_UPDATED", module: "OTUA", label: "Tenant updated" },
  { action: "TENANT_DEACTIVATED", module: "OTUA", label: "Tenant deactivated" },
  { action: "ACCESS_DENIED", module: "ASM", label: "Access denied" },
  { action: "AUDIT_LOG_EXPORTED", module: "ABDR", label: "Audit log exported" },
  { action: "OUTBOX_EVENT_PUBLISH_FAILED", module: "SHAD", label: "Outbox event publish failed" },
  { action: "DOCUMENT_TYPE_CREATED", module: "MDTM", label: "Document type created" },
  { action: "DOCUMENT_TYPE_UPDATED", module: "MDTM", label: "Document type updated" },
  { action: "DOCUMENT_TYPE_ACTIVATED", module: "MDTM", label: "Document type activated" },
  { action: "DOCUMENT_TYPE_DEACTIVATED", module: "MDTM", label: "Document type deactivated" },
  { action: "DOCUMENT_TYPE_SCHEMA_PUBLISHED", module: "MDTM", label: "Document type schema published" },
  { action: "METADATA_FIELD_CREATED", module: "MDTM", label: "Metadata field created" },
  { action: "METADATA_FIELD_UPDATED", module: "MDTM", label: "Metadata field updated" },
  { action: "METADATA_FIELD_ACTIVATED", module: "MDTM", label: "Metadata field activated" },
  { action: "METADATA_FIELD_DEACTIVATED", module: "MDTM", label: "Metadata field deactivated" },
  { action: "METADATA_FIELD_DEPRECATED", module: "MDTM", label: "Metadata field deprecated" },
  { action: "METADATA_FIELD_DELETED", module: "MDTM", label: "Metadata field deleted" },
  { action: "METADATA_FIELD_OPTION_CREATED", module: "MDTM", label: "Metadata field option created" },
  { action: "METADATA_FIELD_OPTION_UPDATED", module: "MDTM", label: "Metadata field option updated" },
  { action: "METADATA_FIELD_OPTION_DELETED", module: "MDTM", label: "Metadata field option deleted" },
  { action: "METADATA_FIELD_VALIDATION_CREATED", module: "MDTM", label: "Metadata field validation rule created" },
  { action: "METADATA_FIELD_VALIDATION_UPDATED", module: "MDTM", label: "Metadata field validation rule updated" },
  { action: "METADATA_FIELD_VALIDATION_DELETED", module: "MDTM", label: "Metadata field validation rule deleted" },
]

export const AUDIT_MODULE_LABELS: Record<string, string> = {
  ASM: "Access and Security Management",
  OTUA: "Organization, Tenant and User Administration",
  ABDR: "Comprehensive Audit Management",
  SHAD: "System Health and Administration Dashboard",
  MDTM: "Metadata and Document-Type Management",
}

export function auditModuleOptions(): string[] {
  return Array.from(new Set(AUDIT_ACTION_CATALOG.map((entry) => entry.module)))
}

/** Every module's actions if `modules` is empty - the cascade's "no module selected yet" state. */
export function auditActionOptionsForModules(modules: string[]): AuditActionCatalogEntry[] {
  return modules.length === 0
    ? AUDIT_ACTION_CATALOG
    : AUDIT_ACTION_CATALOG.filter((entry) => modules.includes(entry.module))
}
