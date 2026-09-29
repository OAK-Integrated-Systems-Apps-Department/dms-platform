-- Seeds the platform-wide permission_modules and permission_actions
-- catalog from the DMS Modularized Requirement Registry (32 functional
-- modules, 92 capability-level actions). Module codes match the FR-ID
-- prefixes used in the requirement registry (e.g. ASM = Access and
-- Security Management). Fine-grained CRUD steps within an action are
-- intentionally excluded, per the registry.
--
-- Checked for duplicates before writing this migration: no duplicate
-- module_code, no duplicate (module, action_code) pair, and no duplicate
-- action name within the same module across all 32 modules / 92 actions.

-- =====================================================================
-- permission_modules
-- =====================================================================
INSERT INTO permission_modules (module_code, module_name) VALUES
    ('ASM', 'Access and Security Management'),
    ('DE', 'Document Encryption'),
    ('DW', 'Document Watermarking'),
    ('DSI', 'Digital Signature Integration'),
    ('SEA', 'Sharing and External Access'),
    ('DOT', 'Document Ownership and Transfer'),
    ('OTUA', 'Organization, Tenant and User Administration'),
    ('DMA', 'Data Migration Administration'),
    ('FIE', 'File Import/Export'),
    ('VMSBDI', 'Virus and Malware Scanning Before Document Ingestion'),
    ('DFUP', 'Duplicate File Upload Prevention'),
    ('OS', 'OCR & Scanning'),
    ('DSC', 'Document Storage & Centralization'),
    ('DLM', 'Document Lifecycle Management'),
    ('MDTM', 'Metadata and Document-Type Management'),
    ('DRM', 'Document Relationships Management'),
    ('DVC', 'Document Version Control'),
    ('CICO', 'Check-in, Check-out and Collaborative Co-Editing'),
    ('WA', 'Workflow Automation'),
    ('N', 'Notification'),
    ('DSR', 'Document Search & Retrieval'),
    ('DPRM', 'Document Preview and Rendition Management'),
    ('PID', 'Pin Important Documents'),
    ('BD', 'Bookmark Documents'),
    ('OMD', 'Operational and Management Dashboard'),
    ('SHAD', 'System Health and Administration Dashboard'),
    ('CRG', 'Custom Report Generation'),
    ('CRM', 'Records Retention & Compliance Management'),
    ('ABDR', 'Comprehensive Audit Management'),
    ('BRDR', 'Backup, Restoration and Disaster Recovery'),
    ('RBR', 'Recycle Bin and Recovery'),
    ('IAM', 'Third-Party Integration')
ON CONFLICT (module_code) DO NOTHING;

-- =====================================================================
-- permission_actions
-- =====================================================================
-- Inserted via a VALUES list joined back to permission_modules by
-- module_code, so this migration doesn't need to guess module_id UUIDs.
INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, v.action_code, v.action_name
FROM (VALUES
    ('ASM', 'MANAGE_ROLES_AND_PERMISSIONS', 'Manage Roles & Permissions'),
    ('ASM', 'CONFIGURE_AUTHENTICATION_POLICY', 'Configure Authentication Policy'),
    ('ASM', 'MONITOR_SECURITY_EVENTS', 'Monitor Security Events'),
    ('ASM', 'VIEW_ACCESS_AND_SECURITY_AUDIT_LOG', 'View Access & Security Audit Log'),
    ('DE', 'MANAGE_ENCRYPTION_KEYS', 'Manage Encryption Keys'),
    ('DE', 'VIEW_ENCRYPTION_AUDIT_LOG', 'View Encryption Audit Log'),
    ('DW', 'CONFIGURE_WATERMARK_POLICY', 'Configure Watermark Policy'),
    ('DW', 'VIEW_WATERMARK_AUDIT_LOG', 'View Watermark Audit Log'),
    ('DSI', 'SIGN_DOCUMENTS', 'Sign Documents'),
    ('DSI', 'MANAGE_SIGNATURE_CONFIGURATION', 'Manage Signature Configuration'),
    ('DSI', 'VIEW_SIGNATURE_HISTORY', 'View Signature History'),
    ('SEA', 'CREATE_EXTERNAL_SHARE_LINKS', 'Create External Share Links'),
    ('SEA', 'MANAGE_GUEST_ACCESS', 'Manage Guest Access'),
    ('SEA', 'REVOKE_SHARE_LINKS', 'Revoke Share Links'),
    ('SEA', 'VIEW_SHARING_ACTIVITY_LOG', 'View Sharing Activity Log'),
    ('DOT', 'TRANSFER_DOCUMENT_OWNERSHIP', 'Transfer Document Ownership'),
    ('DOT', 'BULK_TRANSFER_OWNERSHIP', 'Bulk Transfer Ownership'),
    ('DOT', 'VIEW_OWNERSHIP_HISTORY', 'View Ownership History'),
    ('OTUA', 'MANAGE_TENANTS', 'Manage Tenants'),
    ('OTUA', 'MANAGE_USERS', 'Manage Users'),
    ('OTUA', 'MANAGE_ORGANIZATION_SETTINGS', 'Manage Organization Settings'),
    ('OTUA', 'VIEW_TENANT_USAGE', 'View Tenant Usage'),
    ('DMA', 'CONFIGURE_MIGRATION_MAPPING', 'Configure Migration Mapping'),
    ('DMA', 'RUN_AND_COMMIT_MIGRATION_BATCH', 'Run & Commit Migration Batch'),
    ('DMA', 'VIEW_MIGRATION_HISTORY', 'View Migration History'),
    ('FIE', 'BULK_IMPORT_DOCUMENTS', 'Bulk Import Documents'),
    ('FIE', 'BULK_EXPORT_DOCUMENTS', 'Bulk Export Documents'),
    ('FIE', 'MANAGE_IMPORT_TEMPLATES', 'Manage Import Templates'),
    ('VMSBDI', 'REVIEW_QUARANTINED_FILES', 'Review Quarantined Files'),
    ('VMSBDI', 'MANAGE_SCANNING_CONFIGURATION', 'Manage Scanning Configuration'),
    ('DFUP', 'RESOLVE_DUPLICATE_UPLOADS', 'Resolve Duplicate Uploads'),
    ('DFUP', 'CONFIGURE_DUPLICATE_DETECTION_RULES', 'Configure Duplicate Detection Rules'),
    ('OS', 'PERFORM_OCR_EXTRACTION', 'Perform OCR Extraction'),
    ('OS', 'CONFIGURE_OCR_ZONES_TEMPLATES', 'Configure OCR Zones/Templates'),
    ('OS', 'REVIEW_OCR_EXCEPTIONS', 'Review OCR Exceptions'),
    ('DSC', 'MANAGE_FOLDER_STRUCTURE', 'Manage Folder Structure'),
    ('DSC', 'MANAGE_TAG_TAXONOMY', 'Manage Tag Taxonomy'),
    ('DSC', 'CONFIGURE_FOLDER_ACCESS_CONTROL', 'Configure Folder Access Control'),
    ('DLM', 'SUBMIT_DOCUMENTS_FOR_REVIEW', 'Submit Documents for Review'),
    ('DLM', 'REVIEW_AND_APPROVE_DOCUMENTS', 'Review & Approve Documents'),
    ('DLM', 'PUBLISH_DOCUMENTS', 'Publish Documents'),
    ('DLM', 'ARCHIVE_DOCUMENTS', 'Archive Documents'),
    ('DLM', 'APPROVE_DOCUMENT_DISPOSAL', 'Approve Document Disposal'),
    ('DLM', 'RESTORE_DOCUMENTS', 'Restore Documents'),
    ('MDTM', 'MANAGE_CONTENT_TYPES', 'Manage Content Types'),
    ('MDTM', 'MANAGE_METADATA_SCHEMA', 'Manage Metadata Schema'),
    ('DRM', 'MANAGE_DOCUMENT_RELATIONSHIPS', 'Manage Document Relationships'),
    ('DRM', 'CONFIGURE_RELATIONSHIP_TYPES', 'Configure Relationship Types'),
    ('DVC', 'VIEW_VERSION_HISTORY', 'View Version History'),
    ('DVC', 'RESTORE_PRIOR_VERSION', 'Restore Prior Version'),
    ('DVC', 'PROTECT_VERSIONS', 'Protect Versions'),
    ('DVC', 'CONFIGURE_VERSION_RETENTION_RULES', 'Configure Version Retention Rules'),
    ('CICO', 'CHECK_OUT_CHECK_IN_DOCUMENTS', 'Check-out / Check-in Documents'),
    ('CICO', 'FORCE_RELEASE_LOCK', 'Force-Release Lock'),
    ('CICO', 'CONFIGURE_EDITING_ENGINE', 'Configure Editing Engine'),
    ('WA', 'CONFIGURE_WORKFLOW_TEMPLATES', 'Configure Workflow Templates'),
    ('WA', 'APPROVE_REJECT_WORKFLOW_TASK', 'Approve/Reject Workflow Task'),
    ('WA', 'REASSIGN_WORKFLOW_TASK', 'Reassign Workflow Task'),
    ('WA', 'VIEW_WORKFLOW_AUDIT_TRAIL', 'View Workflow Audit Trail'),
    ('N', 'CONFIGURE_NOTIFICATION_RULES', 'Configure Notification Rules'),
    ('N', 'CONFIGURE_ESCALATION_THRESHOLDS', 'Configure Escalation Thresholds'),
    ('DSR', 'SEARCH_DOCUMENTS', 'Search Documents'),
    ('DSR', 'MANAGE_SAVED_SEARCH_PRESETS', 'Manage Saved Search Presets'),
    ('DPRM', 'VIEW_DOCUMENT_PREVIEW', 'View Document Preview'),
    ('DPRM', 'CONFIGURE_PREVIEW_SECURITY_POLICY', 'Configure Preview Security Policy'),
    ('PID', 'PIN_UNPIN_DOCUMENTS', 'Pin/Unpin Documents'),
    ('PID', 'CONFIGURE_PIN_LIMITS', 'Configure Pin Limits'),
    ('BD', 'BOOKMARK_UNBOOKMARK_DOCUMENTS', 'Bookmark/Unbookmark Documents'),
    ('OMD', 'VIEW_OPERATIONAL_DASHBOARD', 'View Operational Dashboard'),
    ('OMD', 'EXPORT_OPERATIONAL_REPORTS', 'Export Operational Reports'),
    ('SHAD', 'VIEW_SYSTEM_HEALTH_DASHBOARD', 'View System Health Dashboard'),
    ('SHAD', 'EXPORT_SYSTEM_HEALTH_REPORTS', 'Export System Health Reports'),
    ('CRG', 'GENERATE_CUSTOM_REPORTS', 'Generate Custom Reports'),
    ('CRG', 'SCHEDULE_AUTOMATED_REPORTS', 'Schedule Automated Reports'),
    ('CRG', 'MANAGE_REPORT_TEMPLATES', 'Manage Report Templates'),
    ('CRM', 'MANAGE_RETENTION_POLICIES', 'Manage Retention Policies'),
    ('CRM', 'CHANGE_DOCUMENT_STATUS_ACTIVE_INACTIVE', 'Change Document Status (Active/Inactive)'),
    ('CRM', 'PLACE_LEGAL_HOLD', 'Place Legal Hold'),
    ('CRM', 'LIFT_LEGAL_HOLD', 'Lift Legal Hold'),
    ('ABDR', 'VIEW_AUDIT_HISTORY', 'View Audit History'),
    ('ABDR', 'EXPORT_AUDIT_REPORTS', 'Export Audit Reports'),
    ('ABDR', 'CONFIGURE_AUDIT_ALERT_RULES', 'Configure Audit Alert Rules'),
    ('BRDR', 'MANAGE_BACKUP_CONFIGURATION', 'Manage Backup Configuration'),
    ('BRDR', 'INITIATE_RESTORATION', 'Initiate Restoration'),
    ('BRDR', 'EXECUTE_DISASTER_RECOVERY_PROCEDURE', 'Execute Disaster Recovery Procedure'),
    ('RBR', 'RESTORE_DELETED_DOCUMENTS', 'Restore Deleted Documents'),
    ('RBR', 'PERMANENTLY_PURGE_DOCUMENTS', 'Permanently Purge Documents'),
    ('RBR', 'MANAGE_RECYCLE_BIN_RETENTION', 'Manage Recycle Bin Retention'),
    ('IAM', 'MANAGE_API_ACCESS_AND_KEYS', 'Manage API Access & Keys'),
    ('IAM', 'MANAGE_WEBHOOK_SUBSCRIPTIONS', 'Manage Webhook Subscriptions'),
    ('IAM', 'MANAGE_THIRD_PARTY_CONNECTORS', 'Manage Third-Party Connectors'),
    ('IAM', 'VIEW_INTEGRATION_ACTIVITY_LOG', 'View Integration Activity Log')
) AS v(module_code, action_code, action_name)
JOIN permission_modules pm ON pm.module_code = v.module_code
ON CONFLICT (module_id, action_code) DO NOTHING;

-- =====================================================================
-- Post-insert sanity check: row counts must match the source registry
-- exactly (32 modules, 92 actions). Catches a silent ON CONFLICT skip
-- from a pre-existing row this migration didn't expect.
-- =====================================================================
DO $$
DECLARE
    module_count INT;
    action_count INT;
BEGIN
    SELECT COUNT(*) INTO module_count FROM permission_modules
        WHERE module_code IN ('ASM', 'DE', 'DW', 'DSI', 'SEA', 'DOT', 'OTUA', 'DMA', 'FIE', 'VMSBDI', 'DFUP', 'OS', 'DSC', 'DLM', 'MDTM', 'DRM', 'DVC', 'CICO', 'WA', 'N', 'DSR', 'DPRM', 'PID', 'BD', 'OMD', 'SHAD', 'CRG', 'CRM', 'ABDR', 'BRDR', 'RBR', 'IAM');
    SELECT COUNT(*) INTO action_count FROM permission_actions pa
        JOIN permission_modules pm ON pm.module_id = pa.module_id
        WHERE pm.module_code IN ('ASM', 'DE', 'DW', 'DSI', 'SEA', 'DOT', 'OTUA', 'DMA', 'FIE', 'VMSBDI', 'DFUP', 'OS', 'DSC', 'DLM', 'MDTM', 'DRM', 'DVC', 'CICO', 'WA', 'N', 'DSR', 'DPRM', 'PID', 'BD', 'OMD', 'SHAD', 'CRG', 'CRM', 'ABDR', 'BRDR', 'RBR', 'IAM');

    IF module_count <> 32 THEN
        RAISE EXCEPTION 'Expected 32 seeded modules, found %', module_count;
    END IF;
    IF action_count <> 92 THEN
        RAISE EXCEPTION 'Expected 92 seeded actions, found %', action_count;
    END IF;
END $$;
