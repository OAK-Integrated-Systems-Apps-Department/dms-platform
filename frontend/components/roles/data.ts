import type { PermissionGroup, RoleRecord } from "./types"

/**
 * Permissions grouped by DMS functional module, sourced from the
 * Modularized Requirement Registry (32 modules, ~90 capability-level
 * actions). Each id is `${moduleCode}.${action-slug}`, e.g.
 * "otua.manage-users". Swap for a fetch/query hook later — see
 * `components/dashboard/data.ts` for the same pattern.
 */
export const permissionGroups: PermissionGroup[] = [
  {
    module: "Access and Security Management",
    moduleCode: "ASM",
    permissions: [
      {
        id: "asm.manage-roles-and-permissions",
        label: "Manage Roles & Permissions",
      },
      {
        id: "asm.configure-authentication-policy",
        label: "Configure Authentication Policy",
      },
      { id: "asm.monitor-security-events", label: "Monitor Security Events" },
      {
        id: "asm.view-access-and-security-audit-log",
        label: "View Access & Security Audit Log",
      },
    ],
  },
  {
    module: "Document Encryption",
    moduleCode: "DE",
    permissions: [
      { id: "de.manage-encryption-keys", label: "Manage Encryption Keys" },
      {
        id: "de.view-encryption-audit-log",
        label: "View Encryption Audit Log",
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
      },
      { id: "dw.view-watermark-audit-log", label: "View Watermark Audit Log" },
    ],
  },
  {
    module: "Digital Signature Integration",
    moduleCode: "DSI",
    permissions: [
      { id: "dsi.sign-documents", label: "Sign Documents" },
      {
        id: "dsi.manage-signature-configuration",
        label: "Manage Signature Configuration",
      },
      { id: "dsi.view-signature-history", label: "View Signature History" },
    ],
  },
  {
    module: "Sharing and External Access",
    moduleCode: "SEA",
    permissions: [
      {
        id: "sea.create-external-share-links",
        label: "Create External Share Links",
      },
      { id: "sea.manage-guest-access", label: "Manage Guest Access" },
      { id: "sea.revoke-share-links", label: "Revoke Share Links" },
      {
        id: "sea.view-sharing-activity-log",
        label: "View Sharing Activity Log",
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
      },
      { id: "dot.bulk-transfer-ownership", label: "Bulk Transfer Ownership" },
      { id: "dot.view-ownership-history", label: "View Ownership History" },
    ],
  },
  {
    module: "Organization, Tenant and User Administration",
    moduleCode: "OTUA",
    permissions: [
      { id: "otua.manage-tenants", label: "Manage Tenants" },
      { id: "otua.manage-users", label: "Manage Users" },
      {
        id: "otua.manage-organization-settings",
        label: "Manage Organization Settings",
      },
      { id: "otua.view-tenant-usage", label: "View Tenant Usage" },
    ],
  },
  {
    module: "Data Migration Administration",
    moduleCode: "DMA",
    permissions: [
      {
        id: "dma.configure-migration-mapping",
        label: "Configure Migration Mapping",
      },
      {
        id: "dma.run-and-commit-migration-batch",
        label: "Run & Commit Migration Batch",
      },
      { id: "dma.view-migration-history", label: "View Migration History" },
    ],
  },
  {
    module: "File Import/Export",
    moduleCode: "FIE",
    permissions: [
      { id: "fie.bulk-import-documents", label: "Bulk Import Documents" },
      { id: "fie.bulk-export-documents", label: "Bulk Export Documents" },
      { id: "fie.manage-import-templates", label: "Manage Import Templates" },
    ],
  },
  {
    module: "Virus and Malware Scanning Before Document Ingestion",
    moduleCode: "VMSBDI",
    permissions: [
      {
        id: "vmsbdi.review-quarantined-files",
        label: "Review Quarantined Files",
      },
      {
        id: "vmsbdi.manage-scanning-configuration",
        label: "Manage Scanning Configuration",
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
      },
      {
        id: "dfup.configure-duplicate-detection-rules",
        label: "Configure Duplicate Detection Rules",
      },
    ],
  },
  {
    module: "OCR & Scanning",
    moduleCode: "OS",
    permissions: [
      { id: "os.perform-ocr-extraction", label: "Perform OCR Extraction" },
      {
        id: "os.configure-ocr-zones-templates",
        label: "Configure OCR Zones/Templates",
      },
      { id: "os.review-ocr-exceptions", label: "Review OCR Exceptions" },
    ],
  },
  {
    module: "Document Storage & Centralization",
    moduleCode: "DSC",
    permissions: [
      { id: "dsc.manage-folder-structure", label: "Manage Folder Structure" },
      { id: "dsc.manage-tag-taxonomy", label: "Manage Tag Taxonomy" },
      {
        id: "dsc.configure-folder-access-control",
        label: "Configure Folder Access Control",
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
        id: "dlm.review-and-approve-documents",
        label: "Review & Approve Documents",
      },
      { id: "dlm.publish-documents", label: "Publish Documents" },
      { id: "dlm.archive-documents", label: "Archive Documents" },
      {
        id: "dlm.approve-document-disposal",
        label: "Approve Document Disposal",
      },
      { id: "dlm.restore-documents", label: "Restore Documents" },
    ],
  },
  {
    module: "Metadata and Document-Type Management",
    moduleCode: "MDTM",
    permissions: [
      { id: "mdtm.manage-content-types", label: "Manage Content Types" },
      { id: "mdtm.manage-metadata-schema", label: "Manage Metadata Schema" },
    ],
  },
  {
    module: "Document Relationships Management",
    moduleCode: "DRM",
    permissions: [
      {
        id: "drm.manage-document-relationships",
        label: "Manage Document Relationships",
      },
      {
        id: "drm.configure-relationship-types",
        label: "Configure Relationship Types",
      },
    ],
  },
  {
    module: "Document Version Control",
    moduleCode: "DVC",
    permissions: [
      { id: "dvc.view-version-history", label: "View Version History" },
      { id: "dvc.restore-prior-version", label: "Restore Prior Version" },
      { id: "dvc.protect-versions", label: "Protect Versions" },
      {
        id: "dvc.configure-version-retention-rules",
        label: "Configure Version Retention Rules",
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
      },
      { id: "cico.force-release-lock", label: "Force-Release Lock" },
      {
        id: "cico.configure-editing-engine",
        label: "Configure Editing Engine",
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
      },
      {
        id: "wa.approve-reject-workflow-task",
        label: "Approve/Reject Workflow Task",
      },
      { id: "wa.reassign-workflow-task", label: "Reassign Workflow Task" },
      {
        id: "wa.view-workflow-audit-trail",
        label: "View Workflow Audit Trail",
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
      },
      {
        id: "n.configure-escalation-thresholds",
        label: "Configure Escalation Thresholds",
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
      },
    ],
  },
  {
    module: "Pin Important Documents",
    moduleCode: "PID",
    permissions: [
      { id: "pid.pin-unpin-documents", label: "Pin/Unpin Documents" },
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
    ],
  },
  {
    module: "Custom Report Generation",
    moduleCode: "CRG",
    permissions: [
      { id: "crg.generate-custom-reports", label: "Generate Custom Reports" },
      {
        id: "crg.schedule-automated-reports",
        label: "Schedule Automated Reports",
      },
      { id: "crg.manage-report-templates", label: "Manage Report Templates" },
    ],
  },
  {
    module: "Records Retention & Compliance Management",
    moduleCode: "CRM",
    permissions: [
      {
        id: "crm.manage-retention-policies",
        label: "Manage Retention Policies",
      },
      {
        id: "crm.change-document-status-active-inactive",
        label: "Change Document Status (Active/Inactive)",
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
      },
      { id: "brdr.initiate-restoration", label: "Initiate Restoration" },
      {
        id: "brdr.execute-disaster-recovery-procedure",
        label: "Execute Disaster Recovery Procedure",
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
        id: "iam.manage-api-access-and-keys",
        label: "Manage API Access & Keys",
      },
      {
        id: "iam.manage-webhook-subscriptions",
        label: "Manage Webhook Subscriptions",
      },
      {
        id: "iam.manage-third-party-connectors",
        label: "Manage Third-Party Connectors",
      },
      {
        id: "iam.view-integration-activity-log",
        label: "View Integration Activity Log",
      },
    ],
  },
]

const allPermissionIds = permissionGroups.flatMap((group) =>
  group.permissions.map((permission) => permission.id)
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
      "Reviews audit history and manages retention, legal hold, and compliance-related settings.",
    status: "Active",
    assignedUsers: 2,
    permissions: [
      "abdr.view-audit-history",
      "abdr.export-audit-reports",
      "abdr.configure-audit-alert-rules",
      "crm.manage-retention-policies",
      "crm.change-document-status-active-inactive",
      "crm.place-legal-hold",
      "crm.lift-legal-hold",
      "dsr.search-documents",
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
      "wa.approve-reject-workflow-task",
      "wa.reassign-workflow-task",
      "wa.view-workflow-audit-trail",
      "dsr.search-documents",
      "dprm.view-document-preview",
    ],
  },
  {
    id: "role-uploader",
    name: "Uploader",
    description:
      "Imports new documents and submits them into workflows for approval.",
    status: "Active",
    assignedUsers: 3,
    permissions: [
      "fie.bulk-import-documents",
      "dfup.resolve-duplicate-uploads",
      "dlm.submit-documents-for-review",
      "dsr.search-documents",
    ],
  },
  {
    id: "role-viewer",
    name: "Viewer",
    description:
      "Read-only access to search, preview, pin, and bookmark documents shared with them.",
    status: "Inactive",
    assignedUsers: 3,
    permissions: [
      "dsr.search-documents",
      "dprm.view-document-preview",
      "pid.pin-unpin-documents",
      "bd.bookmark-unbookmark-documents",
    ],
  },
]
