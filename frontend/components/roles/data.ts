import { getLeafPermissionIds } from "./permission-utils"
import type { PermissionGroup, RoleRecord } from "./types"

/**
 * Full DMS Permission Catalog — one group per functional module (matching
 * `DMS_Permission_Modules_and_Actions`), each module's catalog-level actions
 * broken down into their fine-grained, individually-grantable permissions
 * (create/update/view/delete-style operations), matching the Module -> Action
 * -> Permission breakdown the catalog intentionally left out at the action
 * level. Actions that are already a single atomic operation (e.g. "Search
 * Documents") stay as one grantable leaf permission instead of being split
 * further.
 */
export const permissionGroups: PermissionGroup[] = [
  {
    module: "Access and Security Management",
    moduleCode: "ASM",
    permissions: [
      {
        id: "asm.manage-roles-permissions",
        label: "Manage Roles & Permissions",
        permissions: [
          {
            id: "asm.manage-roles-permissions.create-role",
            label: "Create role",
          },
          {
            id: "asm.manage-roles-permissions.update-role",
            label: "Update role",
          },
          {
            id: "asm.manage-roles-permissions.delete-role",
            label: "Delete role",
          },
          {
            id: "asm.manage-roles-permissions.view-role",
            label: "View role details",
          },
          {
            id: "asm.manage-roles-permissions.assign-permissions",
            label: "Assign permissions to role",
          },
          {
            id: "asm.manage-roles-permissions.assign-role-to-user",
            label: "Assign role to user",
          },
        ],
      },
      {
        id: "asm.configure-authentication-policy",
        label: "Configure Authentication Policy",
        permissions: [
          {
            id: "asm.configure-authentication-policy.password-policy",
            label: "Configure password policy",
          },
          {
            id: "asm.configure-authentication-policy.mfa-policy",
            label: "Configure multi-factor authentication policy",
          },
          {
            id: "asm.configure-authentication-policy.session-lockout-policy",
            label: "Configure session & account lockout policy",
          },
        ],
      },
      {
        id: "asm.monitor-security-events",
        label: "Monitor Security Events",
        permissions: [
          {
            id: "asm.monitor-security-events.view-security-feed",
            label: "View security event feed",
          },
          {
            id: "asm.monitor-security-events.resolve-alert",
            label: "Acknowledge / resolve security alert",
          },
          {
            id: "asm.monitor-security-events.configure-anomaly-rules",
            label: "Configure anomalous-login detection rules",
          },
        ],
      },
      {
        id: "asm.view-audit-log",
        label: "View Access & Security Audit Log",
        permissions: [
          {
            id: "asm.view-audit-log.view",
            label: "View access & security audit log",
          },
          {
            id: "asm.view-audit-log.search-filter",
            label: "Search / filter audit log entries",
          },
          {
            id: "asm.view-audit-log.export",
            label: "Export access & security audit log",
          },
        ],
      },
    ],
  },
  {
    module: "Document Encryption",
    moduleCode: "DE",
    permissions: [
      {
        id: "de.manage-encryption-keys",
        label: "Manage Encryption Keys",
        permissions: [
          {
            id: "de.manage-encryption-keys.generate-key",
            label: "Generate encryption key",
          },
          {
            id: "de.manage-encryption-keys.rotate-key",
            label: "Rotate encryption key",
          },
          {
            id: "de.manage-encryption-keys.revoke-key",
            label: "Revoke encryption key",
          },
          {
            id: "de.manage-encryption-keys.view-key-metadata",
            label: "View key metadata",
          },
        ],
      },
      {
        id: "de.view-encryption-audit-log",
        label: "View Encryption Audit Log",
        permissions: [
          {
            id: "de.view-encryption-audit-log.view",
            label: "View encryption audit log",
          },
          {
            id: "de.view-encryption-audit-log.export",
            label: "Export encryption audit log",
          },
        ],
      },
    ],
  },
  {
    module: "Document Watermarking",
    moduleCode: "DW",
    permissions: [
      {
        id: "dw.configure-watermark-policy",
        label: "Configure Watermark Policy",
        permissions: [
          {
            id: "dw.configure-watermark-policy.create-template",
            label: "Create watermark template",
          },
          {
            id: "dw.configure-watermark-policy.update-template",
            label: "Update watermark template",
          },
          {
            id: "dw.configure-watermark-policy.toggle-by-type",
            label: "Enable / disable watermarking per document type",
          },
        ],
      },
      {
        id: "dw.view-watermark-audit-log",
        label: "View Watermark Audit Log",
        permissions: [
          {
            id: "dw.view-watermark-audit-log.view",
            label: "View watermark audit log",
          },
          {
            id: "dw.view-watermark-audit-log.export",
            label: "Export watermark audit log",
          },
        ],
      },
    ],
  },
  {
    module: "Digital Signature Integration",
    moduleCode: "DSI",
    permissions: [
      {
        id: "dsi.sign-documents",
        label: "Sign Documents",
        permissions: [
          { id: "dsi.sign-documents.sign", label: "Sign document" },
          {
            id: "dsi.sign-documents.request-external-signature",
            label: "Send document for external signature",
          },
        ],
      },
      {
        id: "dsi.manage-signature-configuration",
        label: "Manage Signature Configuration",
        permissions: [
          {
            id: "dsi.manage-signature-configuration.configure-provider",
            label: "Configure signature provider",
          },
          {
            id: "dsi.manage-signature-configuration.manage-certificates",
            label: "Manage signature certificates",
          },
          {
            id: "dsi.manage-signature-configuration.configure-workflow-rules",
            label: "Configure signature workflow rules",
          },
        ],
      },
      {
        id: "dsi.view-signature-history",
        label: "View Signature History",
        permissions: [
          {
            id: "dsi.view-signature-history.view",
            label: "View signature history",
          },
          {
            id: "dsi.view-signature-history.export",
            label: "Export signature audit report",
          },
        ],
      },
    ],
  },
  {
    module: "Sharing and External Access",
    moduleCode: "SEA",
    permissions: [
      {
        id: "sea.create-external-share-links",
        label: "Create External Share Links",
        permissions: [
          {
            id: "sea.create-external-share-links.create-link",
            label: "Create share link",
          },
          {
            id: "sea.create-external-share-links.set-expiry-password",
            label: "Set link expiry & password",
          },
          {
            id: "sea.create-external-share-links.set-permission-level",
            label: "Set link permission level (view / download)",
          },
        ],
      },
      {
        id: "sea.manage-guest-access",
        label: "Manage Guest Access",
        permissions: [
          {
            id: "sea.manage-guest-access.invite-guest",
            label: "Invite guest user",
          },
          {
            id: "sea.manage-guest-access.update-guest-access",
            label: "Update guest access",
          },
          {
            id: "sea.manage-guest-access.revoke-guest-access",
            label: "Revoke guest access",
          },
          {
            id: "sea.manage-guest-access.view-guest-list",
            label: "View guest access list",
          },
        ],
      },
      {
        id: "sea.revoke-share-links",
        label: "Revoke Share Links",
        permissions: [
          {
            id: "sea.revoke-share-links.revoke-single",
            label: "Revoke individual share link",
          },
          {
            id: "sea.revoke-share-links.revoke-bulk",
            label: "Bulk revoke share links",
          },
        ],
      },
      {
        id: "sea.view-sharing-activity-log",
        label: "View Sharing Activity Log",
        permissions: [
          {
            id: "sea.view-sharing-activity-log.view",
            label: "View sharing activity log",
          },
          {
            id: "sea.view-sharing-activity-log.export",
            label: "Export sharing activity log",
          },
        ],
      },
    ],
  },
  {
    module: "Document Ownership and Transfer",
    moduleCode: "DOT",
    permissions: [
      {
        id: "dot.transfer-document-ownership",
        label: "Transfer Document Ownership",
        permissions: [
          {
            id: "dot.transfer-document-ownership.initiate-transfer",
            label: "Initiate ownership transfer",
          },
          {
            id: "dot.transfer-document-ownership.approve-transfer",
            label: "Approve ownership transfer",
          },
        ],
      },
      {
        id: "dot.bulk-transfer-ownership",
        label: "Bulk Transfer Ownership",
        permissions: [
          {
            id: "dot.bulk-transfer-ownership.select-documents",
            label: "Select documents for bulk transfer",
          },
          {
            id: "dot.bulk-transfer-ownership.execute-transfer",
            label: "Execute bulk ownership transfer",
          },
        ],
      },
      {
        id: "dot.view-ownership-history",
        label: "View Ownership History",
        permissions: [
          {
            id: "dot.view-ownership-history.view",
            label: "View ownership history",
          },
          {
            id: "dot.view-ownership-history.export",
            label: "Export ownership history",
          },
        ],
      },
    ],
  },
  {
    module: "Organization, Tenant and User Administration",
    moduleCode: "OTUA",
    permissions: [
      {
        id: "otua.manage-tenants",
        label: "Manage Tenants",
        permissions: [
          { id: "otua.manage-tenants.create-tenant", label: "Create tenant" },
          { id: "otua.manage-tenants.update-tenant", label: "Update tenant" },
          {
            id: "otua.manage-tenants.suspend-reactivate-tenant",
            label: "Suspend / reactivate tenant",
          },
          { id: "otua.manage-tenants.delete-tenant", label: "Delete tenant" },
          {
            id: "otua.manage-tenants.view-tenant",
            label: "View tenant details",
          },
        ],
      },
      {
        id: "otua.manage-users",
        label: "Manage Users",
        permissions: [
          { id: "otua.manage-users.create", label: "Create user" },
          { id: "otua.manage-users.update", label: "Update user" },
          { id: "otua.manage-users.view", label: "View user details" },
          {
            id: "otua.manage-users.reset-password",
            label: "Reset user password",
          },
          {
            id: "otua.manage-users.activate-deactivate",
            label: "Activate / deactivate user",
          },
          { id: "otua.manage-users.delete", label: "Delete user" },
          { id: "otua.manage-users.assign-role", label: "Assign role to user" },
          { id: "otua.manage-users.bulk-import", label: "Bulk import users" },
        ],
      },
      {
        id: "otua.manage-org-settings",
        label: "Manage Organization Settings",
        permissions: [
          {
            id: "otua.manage-org-settings.update-profile",
            label: "Update organization profile",
          },
          {
            id: "otua.manage-org-settings.configure-policies",
            label: "Configure org-wide policies",
          },
          {
            id: "otua.manage-org-settings.manage-branding",
            label: "Manage branding / letterhead",
          },
        ],
      },
      {
        id: "otua.view-tenant-usage",
        label: "View Tenant Usage",
        permissions: [
          {
            id: "otua.view-tenant-usage.view",
            label: "View tenant usage metrics",
          },
          {
            id: "otua.view-tenant-usage.export",
            label: "Export tenant usage report",
          },
        ],
      },
    ],
  },
  {
    module: "Data Migration Administration",
    moduleCode: "DMA",
    permissions: [
      {
        id: "dma.configure-migration-mapping",
        label: "Configure Migration Mapping",
        permissions: [
          {
            id: "dma.configure-migration-mapping.create-mapping",
            label: "Create field mapping",
          },
          {
            id: "dma.configure-migration-mapping.update-mapping",
            label: "Update field mapping",
          },
          {
            id: "dma.configure-migration-mapping.validate-mapping",
            label: "Validate mapping",
          },
        ],
      },
      {
        id: "dma.run-commit-migration-batch",
        label: "Run & Commit Migration Batch",
        permissions: [
          {
            id: "dma.run-commit-migration-batch.dry-run",
            label: "Run migration batch (dry run)",
          },
          {
            id: "dma.run-commit-migration-batch.commit",
            label: "Commit migration batch",
          },
          {
            id: "dma.run-commit-migration-batch.rollback",
            label: "Rollback migration batch",
          },
        ],
      },
      {
        id: "dma.view-migration-history",
        label: "View Migration History",
        permissions: [
          {
            id: "dma.view-migration-history.view",
            label: "View migration history",
          },
          {
            id: "dma.view-migration-history.export",
            label: "Export migration report",
          },
        ],
      },
    ],
  },
  {
    module: "File Import/Export",
    moduleCode: "FIE",
    permissions: [
      {
        id: "fie.bulk-import-documents",
        label: "Bulk Import Documents",
        permissions: [
          {
            id: "fie.bulk-import-documents.upload-file",
            label: "Upload bulk import file",
          },
          {
            id: "fie.bulk-import-documents.map-fields",
            label: "Map import fields",
          },
          {
            id: "fie.bulk-import-documents.validate-preview",
            label: "Validate & preview import",
          },
          {
            id: "fie.bulk-import-documents.execute-import",
            label: "Execute bulk import",
          },
        ],
      },
      {
        id: "fie.bulk-export-documents",
        label: "Bulk Export Documents",
        permissions: [
          {
            id: "fie.bulk-export-documents.select-documents",
            label: "Select documents for export",
          },
          {
            id: "fie.bulk-export-documents.configure-format",
            label: "Configure export format",
          },
          {
            id: "fie.bulk-export-documents.execute-export",
            label: "Execute bulk export",
          },
        ],
      },
      {
        id: "fie.manage-import-templates",
        label: "Manage Import Templates",
        permissions: [
          {
            id: "fie.manage-import-templates.create-template",
            label: "Create import template",
          },
          {
            id: "fie.manage-import-templates.update-template",
            label: "Update import template",
          },
          {
            id: "fie.manage-import-templates.delete-template",
            label: "Delete import template",
          },
        ],
      },
    ],
  },
  {
    module: "Virus and Malware Scanning Before Document Ingestion",
    moduleCode: "VMSBDI",
    permissions: [
      {
        id: "vmsbdi.review-quarantined-files",
        label: "Review Quarantined Files",
        permissions: [
          {
            id: "vmsbdi.review-quarantined-files.view-quarantine",
            label: "View quarantined files",
          },
          {
            id: "vmsbdi.review-quarantined-files.release-file",
            label: "Release file from quarantine",
          },
          {
            id: "vmsbdi.review-quarantined-files.delete-file",
            label: "Permanently delete quarantined file",
          },
        ],
      },
      {
        id: "vmsbdi.manage-scanning-configuration",
        label: "Manage Scanning Configuration",
        permissions: [
          {
            id: "vmsbdi.manage-scanning-configuration.configure-engine",
            label: "Configure scan engine settings",
          },
          {
            id: "vmsbdi.manage-scanning-configuration.configure-schedule",
            label: "Configure scan schedule",
          },
          {
            id: "vmsbdi.manage-scanning-configuration.configure-quarantine-rules",
            label: "Configure quarantine rules",
          },
        ],
      },
    ],
  },
  {
    module: "Duplicate File Upload Prevention",
    moduleCode: "DFUP",
    permissions: [
      {
        id: "dfup.resolve-duplicate-uploads",
        label: "Resolve Duplicate Uploads",
        permissions: [
          {
            id: "dfup.resolve-duplicate-uploads.view-results",
            label: "View duplicate detection results",
          },
          {
            id: "dfup.resolve-duplicate-uploads.resolve",
            label: "Keep / replace / merge duplicate file",
          },
        ],
      },
      {
        id: "dfup.configure-duplicate-detection-rules",
        label: "Configure Duplicate Detection Rules",
        permissions: [
          {
            id: "dfup.configure-duplicate-detection-rules.configure-method",
            label: "Configure detection method (hash / metadata)",
          },
          {
            id: "dfup.configure-duplicate-detection-rules.set-policy",
            label: "Set duplicate handling policy",
          },
        ],
      },
    ],
  },
  {
    module: "OCR & Scanning",
    moduleCode: "OS",
    permissions: [
      {
        id: "os.perform-ocr-extraction",
        label: "Perform OCR Extraction",
        permissions: [
          {
            id: "os.perform-ocr-extraction.trigger",
            label: "Trigger OCR extraction",
          },
          {
            id: "os.perform-ocr-extraction.edit-text",
            label: "Edit extracted text",
          },
        ],
      },
      {
        id: "os.configure-ocr-zones-templates",
        label: "Configure OCR Zones/Templates",
        permissions: [
          {
            id: "os.configure-ocr-zones-templates.create-template",
            label: "Create OCR template",
          },
          {
            id: "os.configure-ocr-zones-templates.update-template",
            label: "Update OCR template",
          },
          {
            id: "os.configure-ocr-zones-templates.delete-template",
            label: "Delete OCR template",
          },
        ],
      },
      {
        id: "os.review-ocr-exceptions",
        label: "Review OCR Exceptions",
        permissions: [
          {
            id: "os.review-ocr-exceptions.view-queue",
            label: "View OCR exception queue",
          },
          {
            id: "os.review-ocr-exceptions.resolve",
            label: "Correct / resolve OCR exception",
          },
        ],
      },
    ],
  },
  {
    module: "Document Storage & Centralization",
    moduleCode: "DSC",
    permissions: [
      {
        id: "dsc.manage-folder-structure",
        label: "Manage Folder Structure",
        permissions: [
          {
            id: "dsc.manage-folder-structure.create-folder",
            label: "Create folder",
          },
          {
            id: "dsc.manage-folder-structure.rename-move-folder",
            label: "Rename / move folder",
          },
          {
            id: "dsc.manage-folder-structure.delete-folder",
            label: "Delete folder",
          },
        ],
      },
      {
        id: "dsc.manage-tag-taxonomy",
        label: "Manage Tag Taxonomy",
        permissions: [
          { id: "dsc.manage-tag-taxonomy.create-tag", label: "Create tag" },
          { id: "dsc.manage-tag-taxonomy.update-tag", label: "Update tag" },
          { id: "dsc.manage-tag-taxonomy.delete-tag", label: "Delete tag" },
          { id: "dsc.manage-tag-taxonomy.merge-tags", label: "Merge tags" },
        ],
      },
      {
        id: "dsc.configure-folder-access-control",
        label: "Configure Folder Access Control",
        permissions: [
          {
            id: "dsc.configure-folder-access-control.assign-permissions",
            label: "Assign folder-level permissions",
          },
          {
            id: "dsc.configure-folder-access-control.view-access-list",
            label: "View folder access list",
          },
        ],
      },
    ],
  },
  {
    module: "Document Lifecycle Management",
    moduleCode: "DLM",
    permissions: [
      {
        id: "dlm.submit-documents-for-review",
        label: "Submit Documents for Review",
      },
      {
        id: "dlm.review-approve-documents",
        label: "Review & Approve Documents",
        permissions: [
          {
            id: "dlm.review-approve-documents.review",
            label: "Review document",
          },
          {
            id: "dlm.review-approve-documents.approve",
            label: "Approve document",
          },
          {
            id: "dlm.review-approve-documents.reject",
            label: "Reject document",
          },
          {
            id: "dlm.review-approve-documents.request-changes",
            label: "Request changes",
          },
        ],
      },
      {
        id: "dlm.publish-documents",
        label: "Publish Documents",
        permissions: [
          { id: "dlm.publish-documents.publish", label: "Publish document" },
          {
            id: "dlm.publish-documents.unpublish",
            label: "Unpublish document",
          },
        ],
      },
      {
        id: "dlm.archive-documents",
        label: "Archive Documents",
        permissions: [
          { id: "dlm.archive-documents.archive", label: "Archive document" },
          {
            id: "dlm.archive-documents.unarchive",
            label: "Unarchive document",
          },
        ],
      },
      {
        id: "dlm.approve-document-disposal",
        label: "Approve Document Disposal",
        permissions: [
          {
            id: "dlm.approve-document-disposal.approve",
            label: "Approve disposal request",
          },
          {
            id: "dlm.approve-document-disposal.reject",
            label: "Reject disposal request",
          },
        ],
      },
      { id: "dlm.restore-documents", label: "Restore Documents" },
    ],
  },
  {
    module: "Metadata and Document-Type Management",
    moduleCode: "MDTM",
    permissions: [
      {
        id: "mdtm.manage-content-types",
        label: "Manage Content Types",
        permissions: [
          {
            id: "mdtm.manage-content-types.create",
            label: "Create content type",
          },
          {
            id: "mdtm.manage-content-types.update",
            label: "Update content type",
          },
          {
            id: "mdtm.manage-content-types.delete",
            label: "Delete content type",
          },
        ],
      },
      {
        id: "mdtm.manage-metadata-schema",
        label: "Manage Metadata Schema",
        permissions: [
          {
            id: "mdtm.manage-metadata-schema.create-field",
            label: "Create metadata field",
          },
          {
            id: "mdtm.manage-metadata-schema.update-field",
            label: "Update metadata field",
          },
          {
            id: "mdtm.manage-metadata-schema.delete-field",
            label: "Delete metadata field",
          },
          {
            id: "mdtm.manage-metadata-schema.set-validation-rules",
            label: "Set required / validation rules",
          },
        ],
      },
    ],
  },
  {
    module: "Document Relationships Management",
    moduleCode: "DRM",
    permissions: [
      {
        id: "drm.manage-document-relationships",
        label: "Manage Document Relationships",
        permissions: [
          {
            id: "drm.manage-document-relationships.create",
            label: "Create relationship",
          },
          {
            id: "drm.manage-document-relationships.remove",
            label: "Remove relationship",
          },
          {
            id: "drm.manage-document-relationships.view-graph",
            label: "View relationship graph",
          },
        ],
      },
      {
        id: "drm.configure-relationship-types",
        label: "Configure Relationship Types",
        permissions: [
          {
            id: "drm.configure-relationship-types.create-type",
            label: "Create relationship type",
          },
          {
            id: "drm.configure-relationship-types.update-type",
            label: "Update relationship type",
          },
          {
            id: "drm.configure-relationship-types.delete-type",
            label: "Delete relationship type",
          },
        ],
      },
    ],
  },
  {
    module: "Document Version Control",
    moduleCode: "DVC",
    permissions: [
      {
        id: "dvc.view-version-history",
        label: "View Version History",
        permissions: [
          {
            id: "dvc.view-version-history.view",
            label: "View version history",
          },
          { id: "dvc.view-version-history.compare", label: "Compare versions" },
        ],
      },
      { id: "dvc.restore-prior-version", label: "Restore Prior Version" },
      {
        id: "dvc.protect-versions",
        label: "Protect Versions",
        permissions: [
          {
            id: "dvc.protect-versions.lock",
            label: "Lock version from deletion",
          },
          { id: "dvc.protect-versions.unlock", label: "Unlock version" },
        ],
      },
      {
        id: "dvc.configure-version-retention-rules",
        label: "Configure Version Retention Rules",
        permissions: [
          {
            id: "dvc.configure-version-retention-rules.set-max-versions",
            label: "Set maximum versions retained",
          },
          {
            id: "dvc.configure-version-retention-rules.set-retention-period",
            label: "Set version retention period",
          },
        ],
      },
    ],
  },
  {
    module: "Check-in, Check-out and Collaborative Co-Editing",
    moduleCode: "CICO",
    permissions: [
      {
        id: "cico.check-out-check-in-documents",
        label: "Check-out / Check-in Documents",
        permissions: [
          {
            id: "cico.check-out-check-in-documents.check-out",
            label: "Check out document",
          },
          {
            id: "cico.check-out-check-in-documents.check-in",
            label: "Check in document",
          },
          {
            id: "cico.check-out-check-in-documents.discard-checkout",
            label: "Discard checkout",
          },
        ],
      },
      { id: "cico.force-release-lock", label: "Force-Release Lock" },
      {
        id: "cico.configure-editing-engine",
        label: "Configure Editing Engine",
        permissions: [
          {
            id: "cico.configure-editing-engine.select-provider",
            label: "Select editing engine provider",
          },
          {
            id: "cico.configure-editing-engine.configure-connection",
            label: "Configure engine connection settings",
          },
        ],
      },
    ],
  },
  {
    module: "Workflow Automation",
    moduleCode: "WA",
    permissions: [
      {
        id: "wa.configure-workflow-templates",
        label: "Configure Workflow Templates",
        permissions: [
          {
            id: "wa.configure-workflow-templates.create",
            label: "Create workflow template",
          },
          {
            id: "wa.configure-workflow-templates.update",
            label: "Update workflow template",
          },
          {
            id: "wa.configure-workflow-templates.delete",
            label: "Delete workflow template",
          },
          {
            id: "wa.configure-workflow-templates.toggle-active",
            label: "Activate / deactivate workflow",
          },
        ],
      },
      {
        id: "wa.approve-reject-workflow-task",
        label: "Approve/Reject Workflow Task",
        permissions: [
          {
            id: "wa.approve-reject-workflow-task.approve",
            label: "Approve workflow task",
          },
          {
            id: "wa.approve-reject-workflow-task.reject",
            label: "Reject workflow task",
          },
        ],
      },
      { id: "wa.reassign-workflow-task", label: "Reassign Workflow Task" },
      {
        id: "wa.view-workflow-audit-trail",
        label: "View Workflow Audit Trail",
        permissions: [
          {
            id: "wa.view-workflow-audit-trail.view",
            label: "View workflow audit trail",
          },
          {
            id: "wa.view-workflow-audit-trail.export",
            label: "Export workflow audit trail",
          },
        ],
      },
    ],
  },
  {
    module: "Notification",
    moduleCode: "N",
    permissions: [
      {
        id: "n.configure-notification-rules",
        label: "Configure Notification Rules",
        permissions: [
          {
            id: "n.configure-notification-rules.create",
            label: "Create notification rule",
          },
          {
            id: "n.configure-notification-rules.update",
            label: "Update notification rule",
          },
          {
            id: "n.configure-notification-rules.delete",
            label: "Delete notification rule",
          },
        ],
      },
      {
        id: "n.configure-escalation-thresholds",
        label: "Configure Escalation Thresholds",
        permissions: [
          {
            id: "n.configure-escalation-thresholds.set-threshold",
            label: "Set escalation threshold",
          },
          {
            id: "n.configure-escalation-thresholds.set-recipients",
            label: "Set escalation recipients",
          },
        ],
      },
    ],
  },
  {
    module: "Document Search & Retrieval",
    moduleCode: "DSR",
    permissions: [
      { id: "dsr.search-documents", label: "Search Documents" },
      {
        id: "dsr.manage-saved-search-presets",
        label: "Manage Saved Search Presets",
        permissions: [
          {
            id: "dsr.manage-saved-search-presets.create",
            label: "Create saved search",
          },
          {
            id: "dsr.manage-saved-search-presets.update",
            label: "Update saved search",
          },
          {
            id: "dsr.manage-saved-search-presets.delete",
            label: "Delete saved search",
          },
          {
            id: "dsr.manage-saved-search-presets.share",
            label: "Share saved search",
          },
        ],
      },
    ],
  },
  {
    module: "Document Preview and Rendition Management",
    moduleCode: "DPRM",
    permissions: [
      { id: "dprm.view-document-preview", label: "View Document Preview" },
      {
        id: "dprm.configure-preview-security-policy",
        label: "Configure Preview Security Policy",
        permissions: [
          {
            id: "dprm.configure-preview-security-policy.toggle-preview-watermark",
            label: "Enable / disable watermark on preview",
          },
          {
            id: "dprm.configure-preview-security-policy.restrict-print-download",
            label: "Restrict print / download from preview",
          },
          {
            id: "dprm.configure-preview-security-policy.configure-rendition-formats",
            label: "Configure supported rendition formats",
          },
        ],
      },
    ],
  },
  {
    module: "Pin Important Documents",
    moduleCode: "PID",
    permissions: [
      {
        id: "pid.pin-unpin-documents",
        label: "Pin/Unpin Documents",
        permissions: [
          { id: "pid.pin-unpin-documents.pin", label: "Pin document" },
          { id: "pid.pin-unpin-documents.unpin", label: "Unpin document" },
        ],
      },
      { id: "pid.configure-pin-limits", label: "Configure Pin Limits" },
    ],
  },
  {
    module: "Bookmark Documents",
    moduleCode: "BD",
    permissions: [
      {
        id: "bd.bookmark-unbookmark-documents",
        label: "Bookmark/Unbookmark Documents",
        permissions: [
          {
            id: "bd.bookmark-unbookmark-documents.bookmark",
            label: "Bookmark document",
          },
          {
            id: "bd.bookmark-unbookmark-documents.remove-bookmark",
            label: "Remove bookmark",
          },
        ],
      },
    ],
  },
  {
    module: "Operational and Management Dashboard",
    moduleCode: "OMD",
    permissions: [
      {
        id: "omd.view-operational-dashboard",
        label: "View Operational Dashboard",
      },
      {
        id: "omd.export-operational-reports",
        label: "Export Operational Reports",
      },
    ],
  },
  {
    module: "System Health and Administration Dashboard",
    moduleCode: "SHAD",
    permissions: [
      {
        id: "shad.view-system-health-dashboard",
        label: "View System Health Dashboard",
      },
      {
        id: "shad.export-system-health-reports",
        label: "Export System Health Reports",
      },
      {
        id: "shad.view-system-logs",
        label: "View System Logs",
      },
    ],
  },
  {
    module: "Custom Report Generation",
    moduleCode: "CRG",
    permissions: [
      {
        id: "crg.generate-custom-reports",
        label: "Generate Custom Reports",
        permissions: [
          {
            id: "crg.generate-custom-reports.build",
            label: "Build custom report",
          },
          { id: "crg.generate-custom-reports.run", label: "Run custom report" },
        ],
      },
      {
        id: "crg.schedule-automated-reports",
        label: "Schedule Automated Reports",
        permissions: [
          {
            id: "crg.schedule-automated-reports.create-schedule",
            label: "Create report schedule",
          },
          {
            id: "crg.schedule-automated-reports.update-cancel-schedule",
            label: "Update / cancel report schedule",
          },
        ],
      },
      {
        id: "crg.manage-report-templates",
        label: "Manage Report Templates",
        permissions: [
          {
            id: "crg.manage-report-templates.create",
            label: "Create report template",
          },
          {
            id: "crg.manage-report-templates.update",
            label: "Update report template",
          },
          {
            id: "crg.manage-report-templates.delete",
            label: "Delete report template",
          },
        ],
      },
    ],
  },
  {
    module: "Records Retention & Compliance Management",
    moduleCode: "CRM",
    permissions: [
      {
        id: "crm.manage-retention-policies",
        label: "Manage Retention Policies",
        permissions: [
          {
            id: "crm.manage-retention-policies.create",
            label: "Create retention policy",
          },
          {
            id: "crm.manage-retention-policies.update",
            label: "Update retention policy",
          },
          {
            id: "crm.manage-retention-policies.delete",
            label: "Delete retention policy",
          },
        ],
      },
      {
        id: "crm.change-document-status",
        label: "Change Document Status (Active/Inactive)",
        permissions: [
          {
            id: "crm.change-document-status.mark-active",
            label: "Mark document active",
          },
          {
            id: "crm.change-document-status.mark-inactive",
            label: "Mark document inactive",
          },
        ],
      },
      { id: "crm.place-legal-hold", label: "Place Legal Hold" },
      { id: "crm.lift-legal-hold", label: "Lift Legal Hold" },
    ],
  },
  {
    module: "Comprehensive Audit Management",
    moduleCode: "ABDR",
    permissions: [
      { id: "abdr.view-audit-history", label: "View Audit History" },
      { id: "abdr.export-audit-reports", label: "Export Audit Reports" },
      {
        id: "abdr.configure-audit-alert-rules",
        label: "Configure Audit Alert Rules",
        permissions: [
          {
            id: "abdr.configure-audit-alert-rules.create",
            label: "Create audit alert rule",
          },
          {
            id: "abdr.configure-audit-alert-rules.update",
            label: "Update audit alert rule",
          },
          {
            id: "abdr.configure-audit-alert-rules.delete",
            label: "Delete audit alert rule",
          },
        ],
      },
    ],
  },
  {
    module: "Backup, Restoration and Disaster Recovery",
    moduleCode: "BRDR",
    permissions: [
      {
        id: "brdr.manage-backup-configuration",
        label: "Manage Backup Configuration",
        permissions: [
          {
            id: "brdr.manage-backup-configuration.configure-schedule",
            label: "Configure backup schedule",
          },
          {
            id: "brdr.manage-backup-configuration.configure-storage-location",
            label: "Configure backup storage location",
          },
          {
            id: "brdr.manage-backup-configuration.view-job-status",
            label: "View backup job status",
          },
        ],
      },
      {
        id: "brdr.initiate-restoration",
        label: "Initiate Restoration",
        permissions: [
          {
            id: "brdr.initiate-restoration.single-document",
            label: "Initiate single-document restoration",
          },
          {
            id: "brdr.initiate-restoration.full-tenant",
            label: "Initiate full / tenant restoration",
          },
        ],
      },
      {
        id: "brdr.execute-disaster-recovery-procedure",
        label: "Execute Disaster Recovery Procedure",
        permissions: [
          {
            id: "brdr.execute-disaster-recovery-procedure.failover",
            label: "Execute disaster-recovery failover",
          },
          {
            id: "brdr.execute-disaster-recovery-procedure.failback",
            label: "Execute disaster-recovery failback",
          },
        ],
      },
    ],
  },
  {
    module: "Recycle Bin and Recovery",
    moduleCode: "RBR",
    permissions: [
      {
        id: "rbr.restore-deleted-documents",
        label: "Restore Deleted Documents",
      },
      {
        id: "rbr.permanently-purge-documents",
        label: "Permanently Purge Documents",
      },
      {
        id: "rbr.manage-recycle-bin-retention",
        label: "Manage Recycle Bin Retention",
      },
    ],
  },
  {
    module: "Third-Party Integration",
    moduleCode: "IAM",
    permissions: [
      {
        id: "iam.manage-api-access-keys",
        label: "Manage API Access & Keys",
        permissions: [
          {
            id: "iam.manage-api-access-keys.generate-key",
            label: "Generate API key",
          },
          {
            id: "iam.manage-api-access-keys.revoke-key",
            label: "Revoke API key",
          },
          {
            id: "iam.manage-api-access-keys.configure-scopes-limits",
            label: "Configure API scopes / rate limits",
          },
        ],
      },
      {
        id: "iam.manage-webhook-subscriptions",
        label: "Manage Webhook Subscriptions",
        permissions: [
          {
            id: "iam.manage-webhook-subscriptions.create",
            label: "Create webhook subscription",
          },
          {
            id: "iam.manage-webhook-subscriptions.update",
            label: "Update webhook subscription",
          },
          {
            id: "iam.manage-webhook-subscriptions.delete",
            label: "Delete webhook subscription",
          },
        ],
      },
      {
        id: "iam.manage-third-party-connectors",
        label: "Manage Third-Party Connectors",
        permissions: [
          {
            id: "iam.manage-third-party-connectors.install-enable",
            label: "Install / enable connector",
          },
          {
            id: "iam.manage-third-party-connectors.configure",
            label: "Configure connector settings",
          },
          {
            id: "iam.manage-third-party-connectors.disable",
            label: "Disable connector",
          },
        ],
      },
      {
        id: "iam.view-integration-activity-log",
        label: "View Integration Activity Log",
        permissions: [
          {
            id: "iam.view-integration-activity-log.view",
            label: "View integration activity log",
          },
          {
            id: "iam.view-integration-activity-log.export",
            label: "Export integration activity log",
          },
        ],
      },
    ],
  },
]
// Leaf ids only — an action with nested permissions (e.g. "Manage Users")
// is a grouping label, not a grantable id itself, so it's expanded into
// its children here rather than included directly.
const allPermissionIds = getLeafPermissionIds(
  permissionGroups.flatMap((group) => group.permissions)
)

export const initialRoles: RoleRecord[] = [
  {
    id: "role-admin",
    name: "Admin",
    description:
      "Full administrative access, including user, role, and system configuration.",
    status: "Active",
    assignedUsers: 2,
    permissions: allPermissionIds,
    system: true,
  },
  {
    id: "role-compliance-officer",
    name: "Compliance Officer",
    description:
      "Reviews audit history and manages retention, legal hold, and compliance reporting.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "dsr.search-documents",
      "abdr.view-audit-history",
      "abdr.export-audit-reports",
      "abdr.configure-audit-alert-rules.create",
      "asm.view-audit-log.view",
      "asm.view-audit-log.search-filter",
      "asm.view-audit-log.export",
      "crm.manage-retention-policies.create",
      "crm.manage-retention-policies.update",
      "crm.place-legal-hold",
      "crm.lift-legal-hold",
      "crm.change-document-status.mark-active",
      "crm.change-document-status.mark-inactive",
      "crg.generate-custom-reports.build",
      "crg.generate-custom-reports.run",
    ],
  },
  {
    id: "role-approver",
    name: "Approver",
    description:
      "Reviews and approves or rejects documents routed through a workflow.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "dsr.search-documents",
      "dprm.view-document-preview",
      "dlm.review-approve-documents.review",
      "dlm.review-approve-documents.approve",
      "dlm.review-approve-documents.reject",
      "dlm.review-approve-documents.request-changes",
      "wa.approve-reject-workflow-task.approve",
      "wa.approve-reject-workflow-task.reject",
      "wa.reassign-workflow-task",
    ],
  },
  {
    id: "role-uploader",
    name: "Uploader",
    description:
      "Uploads new documents and submits them into workflows for approval.",
    status: "Active",
    assignedUsers: 3,
    permissions: [
      "fie.bulk-import-documents.upload-file",
      "fie.bulk-import-documents.map-fields",
      "fie.bulk-import-documents.validate-preview",
      "fie.bulk-import-documents.execute-import",
      "dlm.submit-documents-for-review",
      "dsr.search-documents",
      "dprm.view-document-preview",
    ],
  },
  {
    id: "role-viewer",
    name: "Viewer",
    description:
      "Read-only access to search, preview, and bookmark documents shared with them.",
    status: "Inactive",
    assignedUsers: 3,
    permissions: [
      "dsr.search-documents",
      "dprm.view-document-preview",
      "dvc.view-version-history.view",
      "bd.bookmark-unbookmark-documents.bookmark",
      "bd.bookmark-unbookmark-documents.remove-bookmark",
    ],
  },
    {
    id: "role-security-administrator",
    name: "Security Administrator",
    description:
      "Manages roles, authentication policy, and monitors security events across the platform.",
    status: "Active",
    assignedUsers: 1,
    permissions: [
      "asm.manage-roles-permissions.create-role",
      "asm.manage-roles-permissions.update-role",
      "asm.manage-roles-permissions.delete-role",
      "asm.manage-roles-permissions.view-role",
      "asm.manage-roles-permissions.assign-permissions",
      "asm.manage-roles-permissions.assign-role-to-user",
      "asm.configure-authentication-policy.password-policy",
      "asm.configure-authentication-policy.mfa-policy",
      "asm.configure-authentication-policy.session-lockout-policy",
      "asm.monitor-security-events.view-security-feed",
      "asm.monitor-security-events.resolve-alert",
      "asm.monitor-security-events.configure-anomaly-rules",
      "asm.view-audit-log.view",
      "asm.view-audit-log.search-filter",
      "asm.view-audit-log.export",
    ],
  },
  {
    id: "role-encryption-officer",
    name: "Encryption Officer",
    description:
      "Manages encryption keys and reviews the encryption audit trail.",
    status: "Active",
    assignedUsers: 1,
    permissions: [
      "de.manage-encryption-keys.generate-key",
      "de.manage-encryption-keys.rotate-key",
      "de.manage-encryption-keys.revoke-key",
      "de.manage-encryption-keys.view-key-metadata",
      "de.view-encryption-audit-log.view",
      "de.view-encryption-audit-log.export",
    ],
  },
  {
    id: "role-signature-coordinator",
    name: "Signature Coordinator",
    description:
      "Sends documents for signature, configures signature providers, and tracks signature history.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "dsi.sign-documents.sign",
      "dsi.sign-documents.request-external-signature",
      "dsi.manage-signature-configuration.configure-provider",
      "dsi.manage-signature-configuration.manage-certificates",
      "dsi.manage-signature-configuration.configure-workflow-rules",
      "dsi.view-signature-history.view",
      "dsi.view-signature-history.export",
    ],
  },
  {
    id: "role-sharing-manager",
    name: "Sharing Manager",
    description:
      "Creates and revokes external share links and manages guest access.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "sea.create-external-share-links.create-link",
      "sea.create-external-share-links.set-expiry-password",
      "sea.create-external-share-links.set-permission-level",
      "sea.manage-guest-access.invite-guest",
      "sea.manage-guest-access.update-guest-access",
      "sea.manage-guest-access.revoke-guest-access",
      "sea.manage-guest-access.view-guest-list",
      "sea.revoke-share-links.revoke-single",
      "sea.revoke-share-links.revoke-bulk",
      "sea.view-sharing-activity-log.view",
      "sea.view-sharing-activity-log.export",
    ],
  },
  {
    id: "role-records-manager",
    name: "Records Manager",
    description:
      "Manages document ownership transfers and the folder / tag structure of the repository.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "dot.transfer-document-ownership.initiate-transfer",
      "dot.transfer-document-ownership.approve-transfer",
      "dot.bulk-transfer-ownership.select-documents",
      "dot.bulk-transfer-ownership.execute-transfer",
      "dot.view-ownership-history.view",
      "dot.view-ownership-history.export",
      "dsc.manage-folder-structure.create-folder",
      "dsc.manage-folder-structure.rename-move-folder",
      "dsc.manage-folder-structure.delete-folder",
      "dsc.manage-tag-taxonomy.create-tag",
      "dsc.manage-tag-taxonomy.update-tag",
      "dsc.manage-tag-taxonomy.delete-tag",
      "dsc.manage-tag-taxonomy.merge-tags",
      "dsc.configure-folder-access-control.assign-permissions",
      "dsc.configure-folder-access-control.view-access-list",
    ],
  },
  {
    id: "role-metadata-administrator",
    name: "Metadata Administrator",
    description:
      "Defines content types and the metadata schema used across document types.",
    status: "Active",
    assignedUsers: 1,
    permissions: [
      "mdtm.manage-content-types.create",
      "mdtm.manage-content-types.update",
      "mdtm.manage-content-types.delete",
      "mdtm.manage-metadata-schema.create-field",
      "mdtm.manage-metadata-schema.update-field",
      "mdtm.manage-metadata-schema.delete-field",
      "mdtm.manage-metadata-schema.set-validation-rules",
    ],
  },
  {
    id: "role-workflow-designer",
    name: "Workflow Designer",
    description:
      "Builds workflow templates and notification rules that drive document review and approval routing.",
    status: "Active",
    assignedUsers: 1,
    permissions: [
      "wa.configure-workflow-templates.create",
      "wa.configure-workflow-templates.update",
      "wa.configure-workflow-templates.delete",
      "wa.configure-workflow-templates.toggle-active",
      "wa.view-workflow-audit-trail.view",
      "wa.view-workflow-audit-trail.export",
      "n.configure-notification-rules.create",
      "n.configure-notification-rules.update",
      "n.configure-notification-rules.delete",
    ],
  },
  {
    id: "role-integration-administrator",
    name: "Integration Administrator",
    description:
      "Manages API keys, webhook subscriptions, and third-party connectors.",
    status: "Active",
    assignedUsers: 1,
    permissions: [
      "iam.manage-api-access-keys.generate-key",
      "iam.manage-api-access-keys.revoke-key",
      "iam.manage-api-access-keys.configure-scopes-limits",
      "iam.manage-webhook-subscriptions.create",
      "iam.manage-webhook-subscriptions.update",
      "iam.manage-webhook-subscriptions.delete",
      "iam.manage-third-party-connectors.install-enable",
      "iam.manage-third-party-connectors.configure",
      "iam.manage-third-party-connectors.disable",
      "iam.view-integration-activity-log.view",
      "iam.view-integration-activity-log.export",
    ],
  },
  {
    id: "role-report-analyst",
    name: "Report Analyst",
    description:
      "Builds and schedules custom reports and monitors the operational dashboard.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "crg.generate-custom-reports.build",
      "crg.generate-custom-reports.run",
      "crg.schedule-automated-reports.create-schedule",
      "crg.schedule-automated-reports.update-cancel-schedule",
      "crg.manage-report-templates.create",
      "crg.manage-report-templates.update",
      "crg.manage-report-templates.delete",
      "omd.view-operational-dashboard",
      "omd.export-operational-reports",
    ],
  },
  {
    id: "role-backup-recovery-administrator",
    name: "Backup & Recovery Administrator",
    description:
      "Configures backups, executes restorations and disaster-recovery failover, and manages the recycle bin.",
    status: "Active",
    assignedUsers: 1,
    permissions: [
      "brdr.manage-backup-configuration.configure-schedule",
      "brdr.manage-backup-configuration.configure-storage-location",
      "brdr.manage-backup-configuration.view-job-status",
      "brdr.initiate-restoration.single-document",
      "brdr.initiate-restoration.full-tenant",
      "brdr.execute-disaster-recovery-procedure.failover",
      "brdr.execute-disaster-recovery-procedure.failback",
      "shad.view-system-health-dashboard",
      "shad.export-system-health-reports",
      "rbr.restore-deleted-documents",
      "rbr.permanently-purge-documents",
      "rbr.manage-recycle-bin-retention",
    ],
  },
]
