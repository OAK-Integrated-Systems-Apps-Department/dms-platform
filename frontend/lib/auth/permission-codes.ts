/**
 * Single source of truth for the granular permission codes passed to
 * `hasPermission()` / `hasAnyPermission()` (see `use-permissions.ts`) and to
 * `<PermissionGate>` across the Users and Roles admin screens. Mirrors
 * `PermissionCodes.java` on the backend - keep both in sync, and add a code
 * here only once the corresponding backend check exists.
 *
 * `LEGACY_ROLE_VIEW`/`LEGACY_ROLE_MANAGE` are the two coarse codes that
 * remain literally correct to check: `PermissionController` (Permission
 * Catalog screens) was out of scope for the granular sweep and still
 * checks these two directly, unlike `UserController`/`UserRoleController`/
 * `RoleController`/`RolePermissionController`, which now check the
 * granular codes below instead.
 */
export const PermissionCodes = {
  // Users (OTUA - Organization, Tenant and User Administration)
  OTUA_USERS_CREATE: "otua.manage-users.create",
  OTUA_USERS_UPDATE: "otua.manage-users.update",
  OTUA_USERS_VIEW: "otua.manage-users.view",
  OTUA_USERS_RESET_PASSWORD: "otua.manage-users.reset-password",
  // Split from a single otua.manage-users.activate-deactivate code so
  // either direction can be granted independently — see
  // V20__split_activate_deactivate_permission.sql.
  OTUA_USERS_ACTIVATE: "otua.manage-users.activate",
  OTUA_USERS_DEACTIVATE: "otua.manage-users.deactivate",
  // Now wired to UserController#purgeUser (additive to the tenant-admin
  // bypass, never a replacement for it) — previously cataloged but never
  // checked anywhere.
  OTUA_USERS_DELETE: "otua.manage-users.delete",
  OTUA_USERS_ASSIGN_ROLE: "otua.manage-users.assign-role",
  // Gates the User List's "Act as User" action - see ImpersonationController
  // on the backend.
  OTUA_USERS_IMPERSONATE: "otua.manage-users.impersonate",

  // Roles (ASM - Access and Security Management)
  ASM_ROLES_CREATE_ROLE: "asm.manage-roles-permissions.create-role",
  ASM_ROLES_UPDATE_ROLE: "asm.manage-roles-permissions.update-role",
  ASM_ROLES_DELETE_ROLE: "asm.manage-roles-permissions.delete-role",
  ASM_ROLES_VIEW_ROLE: "asm.manage-roles-permissions.view-role",
  ASM_ROLES_ASSIGN_PERMISSIONS:
    "asm.manage-roles-permissions.assign-permissions",

  // Legacy - Permission Catalog screens only (PermissionController), see doc comment above.
  LEGACY_ROLE_VIEW: "role.view",
  LEGACY_ROLE_MANAGE: "role.manage",

  // Metadata & Document-Type Management - MDTM (module 2.7). Extends the
  // MDTM resource names already seeded on the backend (manage-content-types
  // for document types, manage-metadata-schema for metadata fields) rather
  // than the module's own "document types"/"metadata fields" wording - see
  // PermissionCodes.java's matching doc comment.
  MDTM_CONTENT_TYPES_CREATE: "mdtm.manage-content-types.create",
  MDTM_CONTENT_TYPES_UPDATE: "mdtm.manage-content-types.update",
  MDTM_CONTENT_TYPES_DELETE: "mdtm.manage-content-types.delete",
  MDTM_CONTENT_TYPES_VIEW: "mdtm.manage-content-types.view",
  MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE:
    "mdtm.manage-content-types.activate-deactivate",

  MDTM_METADATA_SCHEMA_CREATE_FIELD: "mdtm.manage-metadata-schema.create-field",
  MDTM_METADATA_SCHEMA_UPDATE_FIELD: "mdtm.manage-metadata-schema.update-field",
  MDTM_METADATA_SCHEMA_DELETE_FIELD: "mdtm.manage-metadata-schema.delete-field",
  MDTM_METADATA_SCHEMA_VIEW_FIELD: "mdtm.manage-metadata-schema.view-field",
  MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD:
    "mdtm.manage-metadata-schema.activate-deactivate-field",
  MDTM_METADATA_SCHEMA_DEPRECATE_FIELD:
    "mdtm.manage-metadata-schema.deprecate-field",
  MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES:
    "mdtm.manage-metadata-schema.set-validation-rules",

  MDTM_TYPE_FIELD_MAPPING_VIEW: "mdtm.manage-type-field-mapping.view",
  MDTM_TYPE_FIELD_MAPPING_UPDATE: "mdtm.manage-type-field-mapping.update",
  MDTM_DOCUMENT_TEMPLATES_CREATE: "mdtm.manage-document-templates.create",
  MDTM_DOCUMENT_TEMPLATES_UPDATE: "mdtm.manage-document-templates.update",
  MDTM_DOCUMENT_TEMPLATES_DELETE: "mdtm.manage-document-templates.delete",
  // System monitoring (SHAD). Tenant admins hold these implicitly; others get them via a role.
  SHAD_VIEW_SYSTEM_HEALTH: "shad.view-system-health-dashboard",
  SHAD_VIEW_SYSTEM_LOGS: "shad.view-system-logs",
  // Audit Events (ABDR - Comprehensive Audit Management)
  ABDR_VIEW_AUDIT_HISTORY: "abdr.view-audit-history",
  // Distinct from ABDR_VIEW_AUDIT_HISTORY - exporting the trail is a
  // stricter capability than viewing it on-screen, gated by its own
  // seeded permission code.
  ABDR_EXPORT_AUDIT_REPORTS: "abdr.export-audit-reports",
  // File Import/Export (FIE) - BulkImportController gates every intake
  // endpoint (template, batch, files, submit, cancel) on this one code.
  FIE_BULK_IMPORT_UPLOAD_FILE: "fie.bulk-import-documents.upload-file",
  // Bulk export - starting one also needs DOCM_DOCUMENTS_VIEW (it reads the
  // documents); see ExportJobController.
  FIE_BULK_EXPORT_EXECUTE: "fie.bulk-export-documents.execute-export",
  // Saved import templates (ImportTemplateController).
  FIE_IMPORT_TEMPLATES_CREATE: "fie.manage-import-templates.create-template",
  FIE_IMPORT_TEMPLATES_UPDATE: "fie.manage-import-templates.update-template",
  FIE_IMPORT_TEMPLATES_DELETE: "fie.manage-import-templates.delete-template",
  // Quarantine review (DOCM - seeded by backend V40, granted to no role by
  // default). OVERRIDE is deliberately separate from RELEASE: it
  // force-releases a file the malware scanner still flags.
  DOCM_QUARANTINE_VIEW: "docm.quarantine.view",
  DOCM_QUARANTINE_RELEASE: "docm.quarantine.release",
  DOCM_QUARANTINE_DELETE: "docm.quarantine.delete",
  DOCM_QUARANTINE_OVERRIDE: "docm.quarantine.override",
  // Uploading documents (POST /api/v1/documents) - also what the upload
  // panel's document-type picker (/document-types/selectable) requires.
  DOCM_DOCUMENTS_CREATE: "docm.documents.create",

  DOCM_DOCUMENTS_VIEW: "docm.documents.view",
  DOCM_DOCUMENTS_DELETE: "docm.documents.delete",
  // Moving documents between folders (V51).
  DOCM_DOCUMENTS_MOVE: "docm.documents.move",
  // Folders - DSC / Manage Folder Structure (V10). Browsing needs DOCM_DOCUMENTS_VIEW.
  DSC_FOLDERS_CREATE: "dsc.manage-folder-structure.create-folder",
  DSC_FOLDERS_RENAME_MOVE: "dsc.manage-folder-structure.rename-move-folder",
  DSC_FOLDERS_DELETE: "dsc.manage-folder-structure.delete-folder",
  // Folder access control (V53) - who can get into a folder.
  DSC_FOLDER_ACCESS_ASSIGN:
    "dsc.configure-folder-access-control.assign-permissions",
  DSC_FOLDER_ACCESS_VIEW:
    "dsc.configure-folder-access-control.view-access-list",
  // Encryption at rest (V55) - the key management screen.
  DE_KEYS_VIEW: "de.manage-encryption-keys.view-key-metadata",
  DE_KEYS_ROTATE: "de.manage-encryption-keys.rotate-key",
  DE_KEYS_GENERATE: "de.manage-encryption-keys.generate-key",
  // Version history (DVC) and the recycle bin (RBR), seeded by V10.
  DVC_VERSIONS_VIEW: "dvc.view-version-history.view",
  DVC_VERSIONS_RESTORE: "dvc.restore-prior-version",
  RBR_RESTORE: "rbr.restore-deleted-documents",
  RBR_PURGE: "rbr.permanently-purge-documents",
  RBR_RETENTION: "rbr.manage-recycle-bin-retention",

  // Reading a document's metadata (GET /api/v1/documents/{id}).
  DOCM_COMMENTS_CREATE: "docm.document-comments.create",

  // Departments (OTUA) - also the approver pools a workflow stage can route
  // to. The department list is additionally readable with
  // WA_TEMPLATES_CREATE/UPDATE so a template designer can pick one.
  OTUA_DEPARTMENTS_VIEW: "otua.manage-departments.view",
  OTUA_DEPARTMENTS_CREATE: "otua.manage-departments.create",
  OTUA_DEPARTMENTS_UPDATE: "otua.manage-departments.update",
  OTUA_DEPARTMENTS_DELETE: "otua.manage-departments.delete",

  // Workflow Automation (WA) - template design, task decisions, and the
  // workflow audit trail. Any single WA_TEMPLATES_* code opens the template
  // screens (list/get), matching the backend's ANY_TEMPLATE_PERMISSION.
  WA_TEMPLATES_CREATE: "wa.configure-workflow-templates.create",
  WA_TEMPLATES_UPDATE: "wa.configure-workflow-templates.update",
  WA_TEMPLATES_DELETE: "wa.configure-workflow-templates.delete",
  WA_TEMPLATES_TOGGLE_ACTIVE: "wa.configure-workflow-templates.toggle-active",
  WA_TASK_APPROVE: "wa.approve-reject-workflow-task.approve",
  WA_TASK_REJECT: "wa.approve-reject-workflow-task.reject",
  WA_TASK_REASSIGN: "wa.reassign-workflow-task",
  WA_REQUEST_INFORMATION: "wa.request-workflow-information.create",
  WA_AUDIT_TRAIL_VIEW: "wa.view-workflow-audit-trail.view",
  // Document Lifecycle Management - starting a workflow on a document.
  DLM_SUBMIT_DOCUMENTS_FOR_REVIEW: "dlm.submit-documents-for-review",

} as const
