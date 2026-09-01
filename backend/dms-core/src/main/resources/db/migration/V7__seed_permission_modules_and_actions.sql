-- Backfills descriptions onto the permission_modules and permission_actions
-- catalog seeded in V6, from the DMS Modularized Requirement Registry
-- (32 functional modules, 92 capability-level actions). Module codes match
-- the FR-ID prefixes used in the requirement registry (e.g. ASM = Access
-- and Security Management). Fine-grained CRUD steps within an action are
-- intentionally excluded, per the registry.
--
-- This migration assumes V6 already inserted all 32 modules and 92
-- actions. It uses ON CONFLICT ... DO UPDATE (not DO NOTHING) so the
-- description column actually gets populated on those existing rows
-- instead of being silently skipped.
--
-- Checked for duplicates before writing this migration: no duplicate
-- module_code, no duplicate (module, action_code) pair, and no duplicate
-- action name within the same module across all 32 modules / 92 actions.

-- =====================================================================
-- permission_modules
-- =====================================================================
INSERT INTO permission_modules (module_code, module_name, description) VALUES
    ('ASM', 'Access and Security Management', 'Controls who can access the platform and how - roles, permissions, authentication policy, and security event monitoring.'),
    ('DE', 'Document Encryption', 'Encrypts documents at rest and in transit and manages the encryption keys used to protect them.'),
    ('DW', 'Document Watermarking', 'Applies and configures visible or invisible watermarks on documents to deter unauthorized distribution.'),
    ('DSI', 'Digital Signature Integration', 'Enables signing documents and integrates with digital signature providers to capture and verify signatures.'),
    ('SEA', 'Sharing and External Access', 'Manages sharing documents with external parties via links or guest access, including revocation and activity tracking.'),
    ('DOT', 'Document Ownership and Transfer', 'Manages transferring ownership of individual or bulk documents between users and tracking that history.'),
    ('OTUA', 'Organization, Tenant and User Administration', 'Administers tenants, organizations, and users, including organization-wide settings and usage reporting.'),
    ('DMA', 'Data Migration Administration', 'Configures and runs migration of documents and metadata into the platform from external systems.'),
    ('FIE', 'File Import/Export', 'Bulk imports and exports documents and manages reusable import templates.'),
    ('VMSBDI', 'Virus and Malware Scanning Before Document Ingestion', 'Scans uploaded files for viruses and malware before they are ingested, and manages quarantined files.'),
    ('DFUP', 'Duplicate File Upload Prevention', 'Detects and resolves duplicate file uploads and configures the rules used to identify duplicates.'),
    ('OS', 'OCR & Scanning', 'Performs optical character recognition on scanned documents and manages OCR templates and exceptions.'),
    ('DSC', 'Document Storage & Centralization', 'Manages the folder structure, tag taxonomy, and access control used to centrally store documents.'),
    ('DLM', 'Document Lifecycle Management', 'Manages a document''s lifecycle from submission and review through publishing, archiving, and disposal.'),
    ('MDTM', 'Metadata and Document-Type Management', 'Manages content types and metadata schemas used to classify and describe documents.'),
    ('DRM', 'Document Relationships Management', 'Manages relationships between related documents and the types of relationships available.'),
    ('DVC', 'Document Version Control', 'Tracks document version history, restores prior versions, and manages version retention rules.'),
    ('CICO', 'Check-in, Check-out and Collaborative Co-Editing', 'Manages document locking for check-in/check-out and configures the collaborative editing engine.'),
    ('WA', 'Workflow Automation', 'Configures automated approval workflows and manages workflow tasks and audit trails.'),
    ('N', 'Notification', 'Configures notification rules and escalation thresholds for platform events.'),
    ('DSR', 'Document Search & Retrieval', 'Provides document search and manages saved search presets.'),
    ('DPRM', 'Document Preview and Rendition Management', 'Renders document previews and configures the security policy applied to previewing documents.'),
    ('PID', 'Pin Important Documents', 'Lets users pin important documents for quick access and configures pin limits.'),
    ('BD', 'Bookmark Documents', 'Lets users bookmark documents for quick access.'),
    ('OMD', 'Operational and Management Dashboard', 'Provides an operational dashboard and exportable reports on day-to-day platform activity.'),
    ('SHAD', 'System Health and Administration Dashboard', 'Provides a system health dashboard and exportable reports on platform infrastructure status.'),
    ('CRG', 'Custom Report Generation', 'Generates and schedules custom reports and manages reusable report templates.'),
    ('CRM', 'Records Retention & Compliance Management', 'Manages retention policies, document status, and legal holds for regulatory compliance.'),
    ('ABDR', 'Comprehensive Audit Management', 'Provides audit history, exportable audit reports, and configurable audit alert rules.'),
    ('BRDR', 'Backup, Restoration and Disaster Recovery', 'Manages backup configuration and executes restoration and disaster recovery procedures.'),
    ('RBR', 'Recycle Bin and Recovery', 'Manages deleted documents in the recycle bin, including restoration, permanent purge, and retention.'),
    ('IAM', 'Third-Party Integration', 'Manages API access, webhooks, and connectors used to integrate with third-party systems.')
ON CONFLICT (module_code) DO UPDATE
    SET description = EXCLUDED.description;

-- =====================================================================
-- permission_actions
-- =====================================================================
-- Inserted via a VALUES list joined back to permission_modules by
-- module_code, so this migration doesn't need to guess module_id UUIDs.
-- ON CONFLICT ... DO UPDATE backfills description onto the rows V6
-- already created (DO NOTHING would silently skip them).
INSERT INTO permission_actions (module_id, action_code, action_name, description)
SELECT pm.module_id, v.action_code, v.action_name, v.description
FROM (VALUES
    ('ASM', 'MANAGE_ROLES_AND_PERMISSIONS', 'Manage Roles & Permissions', 'Create, update, and delete roles and manage which permissions are granted to each role.'),
    ('ASM', 'CONFIGURE_AUTHENTICATION_POLICY', 'Configure Authentication Policy', 'Set platform-wide authentication rules such as password policy, MFA, and session limits.'),
    ('ASM', 'MONITOR_SECURITY_EVENTS', 'Monitor Security Events', 'View real-time security events such as failed logins and suspicious access attempts.'),
    ('ASM', 'VIEW_ACCESS_AND_SECURITY_AUDIT_LOG', 'View Access & Security Audit Log', 'View the historical audit log of access and security-related events.'),
    ('DE', 'MANAGE_ENCRYPTION_KEYS', 'Manage Encryption Keys', 'Create, rotate, and revoke the encryption keys used to protect documents.'),
    ('DE', 'VIEW_ENCRYPTION_AUDIT_LOG', 'View Encryption Audit Log', 'View the audit log of encryption key usage and document encryption events.'),
    ('DW', 'CONFIGURE_WATERMARK_POLICY', 'Configure Watermark Policy', 'Define when and how watermarks are applied to documents, including their content and appearance.'),
    ('DW', 'VIEW_WATERMARK_AUDIT_LOG', 'View Watermark Audit Log', 'View the audit log of watermarking events applied to documents.'),
    ('DSI', 'SIGN_DOCUMENTS', 'Sign Documents', 'Apply a digital signature to a document.'),
    ('DSI', 'MANAGE_SIGNATURE_CONFIGURATION', 'Manage Signature Configuration', 'Configure the digital signature provider and signing settings.'),
    ('DSI', 'VIEW_SIGNATURE_HISTORY', 'View Signature History', 'View the history of signatures applied to a document.'),
    ('SEA', 'CREATE_EXTERNAL_SHARE_LINKS', 'Create External Share Links', 'Generate a link that grants external parties access to a document.'),
    ('SEA', 'MANAGE_GUEST_ACCESS', 'Manage Guest Access', 'Grant, adjust, or remove access for guest (non-tenant) users.'),
    ('SEA', 'REVOKE_SHARE_LINKS', 'Revoke Share Links', 'Revoke a previously created external share link.'),
    ('SEA', 'VIEW_SHARING_ACTIVITY_LOG', 'View Sharing Activity Log', 'View the audit log of sharing and external access activity.'),
    ('DOT', 'TRANSFER_DOCUMENT_OWNERSHIP', 'Transfer Document Ownership', 'Transfer ownership of a single document to another user.'),
    ('DOT', 'BULK_TRANSFER_OWNERSHIP', 'Bulk Transfer Ownership', 'Transfer ownership of multiple documents to another user in a single operation.'),
    ('DOT', 'VIEW_OWNERSHIP_HISTORY', 'View Ownership History', 'View the history of ownership changes for a document.'),
    ('OTUA', 'MANAGE_TENANTS', 'Manage Tenants', 'Create, update, suspend, or delete tenants on the platform.'),
    ('OTUA', 'MANAGE_USERS', 'Manage Users', 'Create, update, deactivate, or delete user accounts within a tenant.'),
    ('OTUA', 'MANAGE_ORGANIZATION_SETTINGS', 'Manage Organization Settings', 'Configure organization-wide settings such as branding and defaults.'),
    ('OTUA', 'VIEW_TENANT_USAGE', 'View Tenant Usage', 'View usage metrics for a tenant, such as storage and active users.'),
    ('DMA', 'CONFIGURE_MIGRATION_MAPPING', 'Configure Migration Mapping', 'Define how source data fields map to platform fields during a migration.'),
    ('DMA', 'RUN_AND_COMMIT_MIGRATION_BATCH', 'Run & Commit Migration Batch', 'Execute a migration batch and commit the migrated data into the platform.'),
    ('DMA', 'VIEW_MIGRATION_HISTORY', 'View Migration History', 'View the history and status of past migration batches.'),
    ('FIE', 'BULK_IMPORT_DOCUMENTS', 'Bulk Import Documents', 'Import multiple documents into the platform in a single operation.'),
    ('FIE', 'BULK_EXPORT_DOCUMENTS', 'Bulk Export Documents', 'Export multiple documents out of the platform in a single operation.'),
    ('FIE', 'MANAGE_IMPORT_TEMPLATES', 'Manage Import Templates', 'Create and maintain reusable templates used for bulk imports.'),
    ('VMSBDI', 'REVIEW_QUARANTINED_FILES', 'Review Quarantined Files', 'Review files that were quarantined by the malware scanner before ingestion.'),
    ('VMSBDI', 'MANAGE_SCANNING_CONFIGURATION', 'Manage Scanning Configuration', 'Configure the virus and malware scanning engine and its rules.'),
    ('DFUP', 'RESOLVE_DUPLICATE_UPLOADS', 'Resolve Duplicate Uploads', 'Review and resolve files flagged as duplicates during upload.'),
    ('DFUP', 'CONFIGURE_DUPLICATE_DETECTION_RULES', 'Configure Duplicate Detection Rules', 'Configure the rules used to detect duplicate file uploads.'),
    ('OS', 'PERFORM_OCR_EXTRACTION', 'Perform OCR Extraction', 'Run optical character recognition to extract text from a scanned document.'),
    ('OS', 'CONFIGURE_OCR_ZONES_TEMPLATES', 'Configure OCR Zones/Templates', 'Define OCR zones and templates used to extract structured data from documents.'),
    ('OS', 'REVIEW_OCR_EXCEPTIONS', 'Review OCR Exceptions', 'Review and correct documents that failed or produced low-confidence OCR results.'),
    ('DSC', 'MANAGE_FOLDER_STRUCTURE', 'Manage Folder Structure', 'Create, rename, move, and delete folders used to organize documents.'),
    ('DSC', 'MANAGE_TAG_TAXONOMY', 'Manage Tag Taxonomy', 'Create and maintain the set of tags available for classifying documents.'),
    ('DSC', 'CONFIGURE_FOLDER_ACCESS_CONTROL', 'Configure Folder Access Control', 'Configure which users or roles can access a given folder.'),
    ('DLM', 'SUBMIT_DOCUMENTS_FOR_REVIEW', 'Submit Documents for Review', 'Submit a document into a review workflow.'),
    ('DLM', 'REVIEW_AND_APPROVE_DOCUMENTS', 'Review & Approve Documents', 'Review a submitted document and approve or reject it.'),
    ('DLM', 'PUBLISH_DOCUMENTS', 'Publish Documents', 'Publish a document, making it available per its access rules.'),
    ('DLM', 'ARCHIVE_DOCUMENTS', 'Archive Documents', 'Move a document into archived status.'),
    ('DLM', 'APPROVE_DOCUMENT_DISPOSAL', 'Approve Document Disposal', 'Approve the permanent disposal of a document at end of lifecycle.'),
    ('DLM', 'RESTORE_DOCUMENTS', 'Restore Documents', 'Restore an archived or disposed document to active status.'),
    ('MDTM', 'MANAGE_CONTENT_TYPES', 'Manage Content Types', 'Create and maintain the document/content types available on the platform.'),
    ('MDTM', 'MANAGE_METADATA_SCHEMA', 'Manage Metadata Schema', 'Define the metadata fields captured for each content type.'),
    ('DRM', 'MANAGE_DOCUMENT_RELATIONSHIPS', 'Manage Document Relationships', 'Create and remove relationships linking related documents.'),
    ('DRM', 'CONFIGURE_RELATIONSHIP_TYPES', 'Configure Relationship Types', 'Define the types of relationships that can be created between documents.'),
    ('DVC', 'VIEW_VERSION_HISTORY', 'View Version History', 'View the full version history of a document.'),
    ('DVC', 'RESTORE_PRIOR_VERSION', 'Restore Prior Version', 'Restore an earlier version of a document as the current version.'),
    ('DVC', 'PROTECT_VERSIONS', 'Protect Versions', 'Lock specific versions of a document to prevent deletion or modification.'),
    ('DVC', 'CONFIGURE_VERSION_RETENTION_RULES', 'Configure Version Retention Rules', 'Configure how many versions are retained and for how long.'),
    ('CICO', 'CHECK_OUT_CHECK_IN_DOCUMENTS', 'Check-out / Check-in Documents', 'Lock a document for exclusive editing and release it back when done.'),
    ('CICO', 'FORCE_RELEASE_LOCK', 'Force-Release Lock', 'Force-release a document lock held by another user.'),
    ('CICO', 'CONFIGURE_EDITING_ENGINE', 'Configure Editing Engine', 'Configure the engine used for collaborative co-editing of documents.'),
    ('WA', 'CONFIGURE_WORKFLOW_TEMPLATES', 'Configure Workflow Templates', 'Create and maintain reusable workflow templates.'),
    ('WA', 'APPROVE_REJECT_WORKFLOW_TASK', 'Approve/Reject Workflow Task', 'Approve or reject a task assigned within a workflow.'),
    ('WA', 'REASSIGN_WORKFLOW_TASK', 'Reassign Workflow Task', 'Reassign a pending workflow task to a different user.'),
    ('WA', 'VIEW_WORKFLOW_AUDIT_TRAIL', 'View Workflow Audit Trail', 'View the audit trail of a workflow''s execution history.'),
    ('N', 'CONFIGURE_NOTIFICATION_RULES', 'Configure Notification Rules', 'Define which events trigger notifications and to whom.'),
    ('N', 'CONFIGURE_ESCALATION_THRESHOLDS', 'Configure Escalation Thresholds', 'Define time thresholds after which unactioned notifications escalate.'),
    ('DSR', 'SEARCH_DOCUMENTS', 'Search Documents', 'Search for documents by content, metadata, or other criteria.'),
    ('DSR', 'MANAGE_SAVED_SEARCH_PRESETS', 'Manage Saved Search Presets', 'Create and maintain reusable saved search presets.'),
    ('DPRM', 'VIEW_DOCUMENT_PREVIEW', 'View Document Preview', 'Preview a document''s content without downloading the original file.'),
    ('DPRM', 'CONFIGURE_PREVIEW_SECURITY_POLICY', 'Configure Preview Security Policy', 'Configure security restrictions applied to document previews, such as watermarking or download blocking.'),
    ('PID', 'PIN_UNPIN_DOCUMENTS', 'Pin/Unpin Documents', 'Pin or unpin a document for quick access.'),
    ('PID', 'CONFIGURE_PIN_LIMITS', 'Configure Pin Limits', 'Configure the maximum number of documents a user may pin.'),
    ('BD', 'BOOKMARK_UNBOOKMARK_DOCUMENTS', 'Bookmark/Unbookmark Documents', 'Bookmark or remove a bookmark on a document for quick access.'),
    ('OMD', 'VIEW_OPERATIONAL_DASHBOARD', 'View Operational Dashboard', 'View the dashboard summarizing day-to-day operational activity.'),
    ('OMD', 'EXPORT_OPERATIONAL_REPORTS', 'Export Operational Reports', 'Export reports generated from the operational dashboard.'),
    ('SHAD', 'VIEW_SYSTEM_HEALTH_DASHBOARD', 'View System Health Dashboard', 'View the dashboard summarizing platform infrastructure health.'),
    ('SHAD', 'EXPORT_SYSTEM_HEALTH_REPORTS', 'Export System Health Reports', 'Export reports generated from the system health dashboard.'),
    ('CRG', 'GENERATE_CUSTOM_REPORTS', 'Generate Custom Reports', 'Build and generate a custom, ad hoc report.'),
    ('CRG', 'SCHEDULE_AUTOMATED_REPORTS', 'Schedule Automated Reports', 'Schedule a report to be generated and delivered automatically on a recurring basis.'),
    ('CRG', 'MANAGE_REPORT_TEMPLATES', 'Manage Report Templates', 'Create and maintain reusable report templates.'),
    ('CRM', 'MANAGE_RETENTION_POLICIES', 'Manage Retention Policies', 'Create and maintain retention policies governing how long documents are kept.'),
    ('CRM', 'CHANGE_DOCUMENT_STATUS_ACTIVE_INACTIVE', 'Change Document Status (Active/Inactive)', 'Change a document''s status between active and inactive for records-management purposes.'),
    ('CRM', 'PLACE_LEGAL_HOLD', 'Place Legal Hold', 'Place a legal hold on a document to prevent its modification or disposal.'),
    ('CRM', 'LIFT_LEGAL_HOLD', 'Lift Legal Hold', 'Remove a previously placed legal hold from a document.'),
    ('ABDR', 'VIEW_AUDIT_HISTORY', 'View Audit History', 'View the platform-wide audit history of user and system actions.'),
    ('ABDR', 'EXPORT_AUDIT_REPORTS', 'Export Audit Reports', 'Export audit history as a report for external review.'),
    ('ABDR', 'CONFIGURE_AUDIT_ALERT_RULES', 'Configure Audit Alert Rules', 'Configure rules that trigger alerts on specific audited events.'),
    ('BRDR', 'MANAGE_BACKUP_CONFIGURATION', 'Manage Backup Configuration', 'Configure backup schedules, targets, and retention for platform data.'),
    ('BRDR', 'INITIATE_RESTORATION', 'Initiate Restoration', 'Initiate restoration of platform data from a backup.'),
    ('BRDR', 'EXECUTE_DISASTER_RECOVERY_PROCEDURE', 'Execute Disaster Recovery Procedure', 'Execute the platform''s disaster recovery procedure.'),
    ('RBR', 'RESTORE_DELETED_DOCUMENTS', 'Restore Deleted Documents', 'Restore a document that was previously deleted to the recycle bin.'),
    ('RBR', 'PERMANENTLY_PURGE_DOCUMENTS', 'Permanently Purge Documents', 'Permanently and irreversibly delete a document from the recycle bin.'),
    ('RBR', 'MANAGE_RECYCLE_BIN_RETENTION', 'Manage Recycle Bin Retention', 'Configure how long deleted documents are retained in the recycle bin before automatic purge.'),
    ('IAM', 'MANAGE_API_ACCESS_AND_KEYS', 'Manage API Access & Keys', 'Issue, rotate, and revoke API keys used by third-party integrations.'),
    ('IAM', 'MANAGE_WEBHOOK_SUBSCRIPTIONS', 'Manage Webhook Subscriptions', 'Create and maintain webhook subscriptions for platform events.'),
    ('IAM', 'MANAGE_THIRD_PARTY_CONNECTORS', 'Manage Third-Party Connectors', 'Configure and maintain connectors to third-party systems.'),
    ('IAM', 'VIEW_INTEGRATION_ACTIVITY_LOG', 'View Integration Activity Log', 'View the audit log of activity performed by third-party integrations.')
) AS v(module_code, action_code, action_name, description)
JOIN permission_modules pm ON pm.module_code = v.module_code
ON CONFLICT (module_id, action_code) DO UPDATE
    SET description = EXCLUDED.description;

-- =====================================================================
-- Post-insert sanity check: row counts must match the source registry
-- exactly (32 modules, 92 actions), and every row must now have a
-- non-null description. Catches a silent skip or a row this migration
-- didn't expect.
-- =====================================================================
DO $$
DECLARE
    module_count INT;
    action_count INT;
    modules_missing_description INT;
    actions_missing_description INT;
BEGIN
    SELECT COUNT(*) INTO module_count FROM permission_modules
        WHERE module_code IN ('ASM', 'DE', 'DW', 'DSI', 'SEA', 'DOT', 'OTUA', 'DMA', 'FIE', 'VMSBDI', 'DFUP', 'OS', 'DSC', 'DLM', 'MDTM', 'DRM', 'DVC', 'CICO', 'WA', 'N', 'DSR', 'DPRM', 'PID', 'BD', 'OMD', 'SHAD', 'CRG', 'CRM', 'ABDR', 'BRDR', 'RBR', 'IAM');
    SELECT COUNT(*) INTO action_count FROM permission_actions pa
        JOIN permission_modules pm ON pm.module_id = pa.module_id
        WHERE pm.module_code IN ('ASM', 'DE', 'DW', 'DSI', 'SEA', 'DOT', 'OTUA', 'DMA', 'FIE', 'VMSBDI', 'DFUP', 'OS', 'DSC', 'DLM', 'MDTM', 'DRM', 'DVC', 'CICO', 'WA', 'N', 'DSR', 'DPRM', 'PID', 'BD', 'OMD', 'SHAD', 'CRG', 'CRM', 'ABDR', 'BRDR', 'RBR', 'IAM');
    SELECT COUNT(*) INTO modules_missing_description FROM permission_modules
        WHERE module_code IN ('ASM', 'DE', 'DW', 'DSI', 'SEA', 'DOT', 'OTUA', 'DMA', 'FIE', 'VMSBDI', 'DFUP', 'OS', 'DSC', 'DLM', 'MDTM', 'DRM', 'DVC', 'CICO', 'WA', 'N', 'DSR', 'DPRM', 'PID', 'BD', 'OMD', 'SHAD', 'CRG', 'CRM', 'ABDR', 'BRDR', 'RBR', 'IAM')
        AND (description IS NULL OR description = '');
    SELECT COUNT(*) INTO actions_missing_description FROM permission_actions pa
        JOIN permission_modules pm ON pm.module_id = pa.module_id
        WHERE pm.module_code IN ('ASM', 'DE', 'DW', 'DSI', 'SEA', 'DOT', 'OTUA', 'DMA', 'FIE', 'VMSBDI', 'DFUP', 'OS', 'DSC', 'DLM', 'MDTM', 'DRM', 'DVC', 'CICO', 'WA', 'N', 'DSR', 'DPRM', 'PID', 'BD', 'OMD', 'SHAD', 'CRG', 'CRM', 'ABDR', 'BRDR', 'RBR', 'IAM')
        AND (pa.description IS NULL OR pa.description = '');

    IF module_count <> 32 THEN
        RAISE EXCEPTION 'Expected 32 seeded modules, found %', module_count;
    END IF;
    IF action_count <> 92 THEN
        RAISE EXCEPTION 'Expected 92 seeded actions, found %', action_count;
    END IF;
    IF modules_missing_description <> 0 THEN
        RAISE EXCEPTION 'Expected all 32 modules to have a description, % are missing one', modules_missing_description;
    END IF;
    IF actions_missing_description <> 0 THEN
        RAISE EXCEPTION 'Expected all 92 actions to have a description, % are missing one', actions_missing_description;
    END IF;
END $$;