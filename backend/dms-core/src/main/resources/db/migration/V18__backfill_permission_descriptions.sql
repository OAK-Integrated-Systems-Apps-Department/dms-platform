-- Backfills a one-sentence description for every permission in the
-- catalog - all 226 rows (the 222 granular permissions seeded by
-- V10__seed_fine_grained_permissions.sql, plus the 4 legacy coarse codes
-- seeded by V4__create_permissions_table.sql) currently have
-- description = NULL, which is what the Permission Catalog page's
-- "No description provided." fallback has been showing for every row.
--
-- Wording is drawn from the "Modularized Requirement Registry" (Version
-- 03, docs/requirement-registry.md)'s per-module Functional Requirements
-- wherever a permission clearly corresponds to one; where no FR maps
-- directly (mostly UI-preference-style actions like pin/bookmark limits),
-- the description is written directly from the permission's own name/code.
-- Every description is one complete, imperative sentence, matching V8's
-- own style for the 4 legacy codes below (reused verbatim from V8, not
-- re-derived) and the sample already confirmed in conversation.
--
-- This does not touch permission_code, name, action_id, or any other
-- column - description only.
--
-- Batch 1: Security & Protection modules - ASM, DE, DW, DSI, SEA
-- (44 permissions).
-- Batch 2: Governance & Administration modules - DOT, OTUA, DMA, FIE
-- (42 permissions).
-- Batch 3: Ingestion & Storage modules - VMSBDI, DFUP, OS, DSC, DLM
-- (38 permissions).
-- Batch 4: Content & Collaboration modules - MDTM, DRM, DVC, CICO, WA
-- (35 permissions).
-- Batch 5: Organization, Retrieval & Reporting modules - N, DSR, DPRM,
-- PID, BD, OMD, SHAD, CRG (30 permissions).
-- Batch 6 (final): Compliance & Integration modules - CRM, ABDR, BRDR,
-- RBR, IAM (33 permissions), plus the 4 legacy coarse permission codes
-- (role.view, role.manage, user.view, user.manage - reused verbatim
-- from V8__backfill_role_permission_descriptions.sql rather than
-- re-derived), followed by the post-backfill sanity check.

UPDATE permissions
SET description = v.description
FROM (VALUES
    -- ASM - Access and Security Management (15)
    ('asm.manage-roles-permissions.create-role', 'Create a new role and define its permission grants.'),
    ('asm.manage-roles-permissions.update-role', 'Update an existing role''s name, description, or status.'),
    ('asm.manage-roles-permissions.delete-role', 'Delete a role from the tenant.'),
    ('asm.manage-roles-permissions.view-role', 'View a role''s details and its granted permissions.'),
    ('asm.manage-roles-permissions.assign-permissions', 'Assign or revoke granular permissions on a role.'),
    ('asm.manage-roles-permissions.assign-role-to-user', 'Assign a role to a user or remove it from them.'),
    ('asm.configure-authentication-policy.password-policy', 'Configure the tenant''s password policy requirements.'),
    ('asm.configure-authentication-policy.mfa-policy', 'Configure multi-factor authentication requirements for logins.'),
    ('asm.configure-authentication-policy.session-lockout-policy', 'Configure session timeout and account lockout rules.'),
    ('asm.monitor-security-events.view-security-feed', 'View the live feed of security events across the tenant.'),
    ('asm.monitor-security-events.resolve-alert', 'Acknowledge or resolve a security alert.'),
    ('asm.monitor-security-events.configure-anomaly-rules', 'Configure rules for detecting anomalous login behavior.'),
    ('asm.view-audit-log.view', 'View the access and security audit log.'),
    ('asm.view-audit-log.search-filter', 'Search and filter entries in the access and security audit log.'),
    ('asm.view-audit-log.export', 'Export the access and security audit log.'),

    -- DE - Document Encryption (6)
    ('de.manage-encryption-keys.generate-key', 'Generate a new encryption key for document storage.'),
    ('de.manage-encryption-keys.rotate-key', 'Rotate an encryption key without requiring documents to be re-uploaded.'),
    ('de.manage-encryption-keys.revoke-key', 'Revoke an encryption key, preventing its further use.'),
    ('de.manage-encryption-keys.view-key-metadata', 'View metadata about an encryption key, such as its status and rotation history.'),
    ('de.view-encryption-audit-log.view', 'View the encryption audit log.'),
    ('de.view-encryption-audit-log.export', 'Export the encryption audit log.'),

    -- DW - Document Watermarking (5)
    ('dw.configure-watermark-policy.create-template', 'Create a watermark template.'),
    ('dw.configure-watermark-policy.update-template', 'Update an existing watermark template.'),
    ('dw.configure-watermark-policy.toggle-by-type', 'Enable or disable watermarking for a specific document type.'),
    ('dw.view-watermark-audit-log.view', 'View the watermark audit log.'),
    ('dw.view-watermark-audit-log.export', 'Export the watermark audit log.'),

    -- DSI - Digital Signature Integration (7)
    ('dsi.sign-documents.sign', 'Digitally sign a document as part of its approval workflow.'),
    ('dsi.sign-documents.request-external-signature', 'Send a document to an external party for signature.'),
    ('dsi.manage-signature-configuration.configure-provider', 'Configure the digital signature provider used for signing.'),
    ('dsi.manage-signature-configuration.manage-certificates', 'Manage the certificates used for digital signatures.'),
    ('dsi.manage-signature-configuration.configure-workflow-rules', 'Configure the workflow rules that govern document signing.'),
    ('dsi.view-signature-history.view', 'View a document''s signature history.'),
    ('dsi.view-signature-history.export', 'Export a signature audit report.'),

    -- SEA - Sharing and External Access (11)
    ('sea.create-external-share-links.create-link', 'Create a secure external share link for a document or folder.'),
    ('sea.create-external-share-links.set-expiry-password', 'Configure an expiry date and password protection for an external share link.'),
    ('sea.create-external-share-links.set-permission-level', 'Set whether an external share link allows viewing only or viewing and downloading.'),
    ('sea.manage-guest-access.invite-guest', 'Invite an external guest user for document collaboration.'),
    ('sea.manage-guest-access.update-guest-access', 'Update an existing guest user''s access.'),
    ('sea.manage-guest-access.revoke-guest-access', 'Revoke a guest user''s access.'),
    ('sea.manage-guest-access.view-guest-list', 'View the list of guest users with external access.'),
    ('sea.revoke-share-links.revoke-single', 'Revoke an individual external share link.'),
    ('sea.revoke-share-links.revoke-bulk', 'Revoke multiple external share links at once.'),
    ('sea.view-sharing-activity-log.view', 'View the external sharing activity log.'),
    ('sea.view-sharing-activity-log.export', 'Export the external sharing activity log.')
) AS v(permission_code, description)
WHERE permissions.permission_code = v.permission_code
  AND permissions.tenant_id IS NULL;

UPDATE permissions
SET description = v.description
FROM (VALUES
    -- DOT - Document Ownership and Transfer (6)
    ('dot.transfer-document-ownership.initiate-transfer', 'Initiate the transfer of a document''s ownership to a new owner.'),
    ('dot.transfer-document-ownership.approve-transfer', 'Approve a pending document ownership transfer.'),
    ('dot.bulk-transfer-ownership.select-documents', 'Select multiple documents for a bulk ownership transfer.'),
    ('dot.bulk-transfer-ownership.execute-transfer', 'Execute a bulk ownership transfer for a departing employee or reorganized department.'),
    ('dot.view-ownership-history.view', 'View a document''s ownership transfer history.'),
    ('dot.view-ownership-history.export', 'Export a document''s ownership transfer history.'),

    -- OTUA - Organization, Tenant and User Administration (18)
    ('otua.manage-tenants.create-tenant', 'Provision a new tenant instance with isolated data and configuration.'),
    ('otua.manage-tenants.update-tenant', 'Update an existing tenant''s configuration.'),
    ('otua.manage-tenants.suspend-reactivate-tenant', 'Suspend or reactivate a tenant.'),
    ('otua.manage-tenants.delete-tenant', 'Deprovision and delete a tenant instance.'),
    ('otua.manage-tenants.view-tenant', 'View a tenant''s details.'),
    ('otua.manage-users.create', 'Create a new user within the tenant.'),
    ('otua.manage-users.update', 'Update an existing user''s profile within the tenant.'),
    ('otua.manage-users.view', 'View user accounts within the tenant.'),
    ('otua.manage-users.reset-password', 'Reset a user''s password within the tenant.'),
    ('otua.manage-users.activate-deactivate', 'Activate or deactivate a user account within the tenant.'),
    ('otua.manage-users.delete', 'Delete a user account from the tenant.'),
    ('otua.manage-users.assign-role', 'Assign a role to a user within the tenant.'),
    ('otua.manage-users.bulk-import', 'Bulk import multiple user accounts into the tenant.'),
    ('otua.manage-org-settings.update-profile', 'Update the organization''s profile information.'),
    ('otua.manage-org-settings.configure-policies', 'Configure organization-level policies such as retention defaults.'),
    ('otua.manage-org-settings.manage-branding', 'Manage the organization''s branding settings.'),
    ('otua.view-tenant-usage.view', 'View tenant-level usage statistics such as storage and user count.'),
    ('otua.view-tenant-usage.export', 'Export tenant-level usage statistics.'),

    -- DMA - Data Migration Administration (8)
    ('dma.configure-migration-mapping.create-mapping', 'Create a field-mapping rule from a source system to the target metadata schema.'),
    ('dma.configure-migration-mapping.update-mapping', 'Update an existing migration field-mapping rule.'),
    ('dma.configure-migration-mapping.validate-mapping', 'Validate a migration field-mapping rule against the target schema.'),
    ('dma.run-commit-migration-batch.dry-run', 'Run a migration batch as a dry run without committing it to production.'),
    ('dma.run-commit-migration-batch.commit', 'Commit a validated migration batch into production.'),
    ('dma.run-commit-migration-batch.rollback', 'Roll back a migration batch that failed validation or reconciliation.'),
    ('dma.view-migration-history.view', 'View the history of migration batches.'),
    ('dma.view-migration-history.export', 'Export the migration batch history.'),

    -- FIE - File Import/Export (10)
    ('fie.bulk-import-documents.upload-file', 'Upload a ZIP archive of documents and metadata for bulk import.'),
    ('fie.bulk-import-documents.map-fields', 'Map template fields to document metadata for a bulk import.'),
    ('fie.bulk-import-documents.validate-preview', 'Validate and preview a bulk import batch before it is created.'),
    ('fie.bulk-import-documents.execute-import', 'Execute a bulk import, creating document records for all rows that pass validation.'),
    ('fie.bulk-export-documents.select-documents', 'Select multiple documents for bulk export.'),
    ('fie.bulk-export-documents.configure-format', 'Configure the export format for a bulk document export.'),
    ('fie.bulk-export-documents.execute-export', 'Execute a bulk export of documents and their metadata.'),
    ('fie.manage-import-templates.create-template', 'Create a metadata import template for a document type or category.'),
    ('fie.manage-import-templates.update-template', 'Update an existing metadata import template.'),
    ('fie.manage-import-templates.delete-template', 'Delete a metadata import template.')
) AS v(permission_code, description)
WHERE permissions.permission_code = v.permission_code
  AND permissions.tenant_id IS NULL;

UPDATE permissions
SET description = v.description
FROM (VALUES
    -- VMSBDI - Virus and Malware Scanning Before Document Ingestion (6)
    ('vmsbdi.review-quarantined-files.view-quarantine', 'View files quarantined after failing a virus or malware scan.'),
    ('vmsbdi.review-quarantined-files.release-file', 'Release a quarantined file confirmed to be a false positive.'),
    ('vmsbdi.review-quarantined-files.delete-file', 'Permanently delete a quarantined file confirmed to contain a threat.'),
    ('vmsbdi.manage-scanning-configuration.configure-engine', 'Configure the virus and malware scanning engine.'),
    ('vmsbdi.manage-scanning-configuration.configure-schedule', 'Configure the schedule for virus and malware signature updates.'),
    ('vmsbdi.manage-scanning-configuration.configure-quarantine-rules', 'Configure the rules governing when an uploaded file is quarantined.'),

    -- DFUP - Duplicate File Upload Prevention (4)
    ('dfup.resolve-duplicate-uploads.view-results', 'View the results of a duplicate-upload detection check.'),
    ('dfup.resolve-duplicate-uploads.resolve', 'Resolve a detected duplicate upload by updating the existing document or cancelling the new one.'),
    ('dfup.configure-duplicate-detection-rules.configure-method', 'Configure the method used to detect duplicate document uploads.'),
    ('dfup.configure-duplicate-detection-rules.set-policy', 'Set the policy applied when a duplicate document upload is detected.'),

    -- OS - OCR & Scanning (7)
    ('os.perform-ocr-extraction.trigger', 'Trigger OCR extraction on a scanned or image-based document.'),
    ('os.perform-ocr-extraction.edit-text', 'Edit text extracted from a document by OCR.'),
    ('os.configure-ocr-zones-templates.create-template', 'Create an OCR zone template for extracting structured data from a document layout.'),
    ('os.configure-ocr-zones-templates.update-template', 'Update an existing OCR zone template.'),
    ('os.configure-ocr-zones-templates.delete-template', 'Delete an OCR zone template.'),
    ('os.review-ocr-exceptions.view-queue', 'View the queue of documents that failed OCR extraction and require manual review.'),
    ('os.review-ocr-exceptions.resolve', 'Resolve a document flagged as an OCR extraction exception.'),

    -- DSC - Document Storage & Centralization (9)
    ('dsc.manage-folder-structure.create-folder', 'Create a folder within the document repository.'),
    ('dsc.manage-folder-structure.rename-move-folder', 'Rename or move a folder within the document repository.'),
    ('dsc.manage-folder-structure.delete-folder', 'Delete a folder from the document repository.'),
    ('dsc.manage-tag-taxonomy.create-tag', 'Create a new tag in the organization-wide tag taxonomy.'),
    ('dsc.manage-tag-taxonomy.update-tag', 'Update an existing tag in the tag taxonomy.'),
    ('dsc.manage-tag-taxonomy.delete-tag', 'Delete a tag from the tag taxonomy.'),
    ('dsc.manage-tag-taxonomy.merge-tags', 'Merge two or more tags in the tag taxonomy into one.'),
    ('dsc.configure-folder-access-control.assign-permissions', 'Assign folder-level access permissions to users or groups.'),
    ('dsc.configure-folder-access-control.view-access-list', 'View the list of users and groups with access to a folder.'),

    -- DLM - Document Lifecycle Management (12)
    ('dlm.submit-documents-for-review', 'Submit a draft document for review.'),
    ('dlm.review-approve-documents.review', 'Review a document submitted for approval.'),
    ('dlm.review-approve-documents.approve', 'Approve a reviewed document for publication.'),
    ('dlm.review-approve-documents.reject', 'Reject a reviewed document, returning it to draft status.'),
    ('dlm.review-approve-documents.request-changes', 'Request changes to a reviewed document, returning it to draft status with comments.'),
    ('dlm.publish-documents.publish', 'Publish an approved document, making it active.'),
    ('dlm.publish-documents.unpublish', 'Unpublish an active document.'),
    ('dlm.archive-documents.archive', 'Archive a document, removing it from default active views.'),
    ('dlm.archive-documents.unarchive', 'Restore an archived document to its active state.'),
    ('dlm.approve-document-disposal.approve', 'Approve the permanent disposal of a document whose retention period has expired.'),
    ('dlm.approve-document-disposal.reject', 'Reject a pending document disposal request.'),
    ('dlm.restore-documents', 'Restore a document from the recycle bin to its prior active state.')
) AS v(permission_code, description)
WHERE permissions.permission_code = v.permission_code
  AND permissions.tenant_id IS NULL;

UPDATE permissions
SET description = v.description
FROM (VALUES
    -- MDTM - Metadata and Document-Type Management (7)
    ('mdtm.manage-content-types.create', 'Create a new content type and define its metadata fields.'),
    ('mdtm.manage-content-types.update', 'Update an existing content type''s metadata fields.'),
    ('mdtm.manage-content-types.delete', 'Delete a content type.'),
    ('mdtm.manage-metadata-schema.create-field', 'Add a new field to the centralized metadata schema.'),
    ('mdtm.manage-metadata-schema.update-field', 'Update a field in the centralized metadata schema.'),
    ('mdtm.manage-metadata-schema.delete-field', 'Delete a field from the centralized metadata schema.'),
    ('mdtm.manage-metadata-schema.set-validation-rules', 'Set validation rules for a metadata field, such as format or allowed values.'),

    -- DRM - Document Relationships Management (6)
    ('drm.manage-document-relationships.create', 'Create a relationship between two documents.'),
    ('drm.manage-document-relationships.remove', 'Remove an existing relationship between two documents.'),
    ('drm.manage-document-relationships.view-graph', 'View a document''s relationships in a visual graph.'),
    ('drm.configure-relationship-types.create-type', 'Create a new document relationship type.'),
    ('drm.configure-relationship-types.update-type', 'Update an existing document relationship type.'),
    ('drm.configure-relationship-types.delete-type', 'Delete a document relationship type.'),

    -- DVC - Document Version Control (7)
    ('dvc.view-version-history.view', 'View a document''s version history.'),
    ('dvc.view-version-history.compare', 'Compare two versions of a document.'),
    ('dvc.restore-prior-version', 'Restore a prior version of a document.'),
    ('dvc.protect-versions.lock', 'Protect a document version from deletion.'),
    ('dvc.protect-versions.unlock', 'Remove protection from a document version, allowing it to be deleted.'),
    ('dvc.configure-version-retention-rules.set-max-versions', 'Set the maximum number of versions retained for a document.'),
    ('dvc.configure-version-retention-rules.set-retention-period', 'Set the retention period applied to old document versions.'),

    -- CICO - Check-in, Check-out and Collaborative Co-Editing (6)
    ('cico.check-out-check-in-documents.check-out', 'Check out a document, placing an exclusive lock on it.'),
    ('cico.check-out-check-in-documents.check-in', 'Check in a document, creating a new version and releasing its lock.'),
    ('cico.check-out-check-in-documents.discard-checkout', 'Discard a checked-out document without creating a new version.'),
    ('cico.force-release-lock', 'Force-release a check-out lock left open by another user.'),
    ('cico.configure-editing-engine.select-provider', 'Select the editing engine used for check-out and co-editing sessions.'),
    ('cico.configure-editing-engine.configure-connection', 'Configure the connection settings for the tenant''s editing engine.'),

    -- WA - Workflow Automation (9)
    ('wa.configure-workflow-templates.create', 'Create a new workflow template.'),
    ('wa.configure-workflow-templates.update', 'Update an existing workflow template.'),
    ('wa.configure-workflow-templates.delete', 'Delete a workflow template.'),
    ('wa.configure-workflow-templates.toggle-active', 'Activate or deactivate a workflow template.'),
    ('wa.approve-reject-workflow-task.approve', 'Approve a workflow task at the current stage.'),
    ('wa.approve-reject-workflow-task.reject', 'Reject a workflow task at the current stage, with a reason.'),
    ('wa.reassign-workflow-task', 'Reassign or delegate a workflow task to another user.'),
    ('wa.view-workflow-audit-trail.view', 'View a document''s workflow audit trail.'),
    ('wa.view-workflow-audit-trail.export', 'Export a workflow audit trail.')
) AS v(permission_code, description)
WHERE permissions.permission_code = v.permission_code
  AND permissions.tenant_id IS NULL;

UPDATE permissions
SET description = v.description
FROM (VALUES
    -- N - Notification (5)
    ('n.configure-notification-rules.create', 'Create a notification rule for a workflow event or document status change.'),
    ('n.configure-notification-rules.update', 'Update an existing notification rule.'),
    ('n.configure-notification-rules.delete', 'Delete a notification rule.'),
    ('n.configure-escalation-thresholds.set-threshold', 'Set the inactivity threshold that triggers a reminder or escalation.'),
    ('n.configure-escalation-thresholds.set-recipients', 'Set the manager or supervisor who receives an escalation notification.'),

    -- DSR - Document Search & Retrieval (5)
    ('dsr.search-documents', 'Search for documents by keyword, metadata, or full-text content.'),
    ('dsr.manage-saved-search-presets.create', 'Save a search filter combination as a personal search preset.'),
    ('dsr.manage-saved-search-presets.update', 'Update an existing saved search preset.'),
    ('dsr.manage-saved-search-presets.delete', 'Delete a saved search preset.'),
    ('dsr.manage-saved-search-presets.share', 'Share a saved search preset with other users.'),

    -- DPRM - Document Preview and Rendition Management (4)
    ('dprm.view-document-preview', 'View an in-browser preview of a document without downloading it.'),
    ('dprm.configure-preview-security-policy.toggle-preview-watermark', 'Enable or disable a watermark on document previews.'),
    ('dprm.configure-preview-security-policy.restrict-print-download', 'Restrict printing or downloading from a document preview.'),
    ('dprm.configure-preview-security-policy.configure-rendition-formats', 'Configure the formats generated for document previews and renditions.'),

    -- PID - Pin Important Documents (3)
    ('pid.pin-unpin-documents.pin', 'Pin a document to the shared Pinned Documents section.'),
    ('pid.pin-unpin-documents.unpin', 'Unpin a document from the shared Pinned Documents section.'),
    ('pid.configure-pin-limits', 'Configure the maximum number of documents a user can pin.'),

    -- BD - Bookmark Documents (2)
    ('bd.bookmark-unbookmark-documents.bookmark', 'Bookmark a document for quick access from a personal bookmarks list.'),
    ('bd.bookmark-unbookmark-documents.remove-bookmark', 'Remove a document from a personal bookmarks list.'),

    -- OMD - Operational and Management Dashboard (2)
    ('omd.view-operational-dashboard', 'View the operational dashboard showing document activity KPIs across the organization.'),
    ('omd.export-operational-reports', 'Export reports and data from the operational dashboard.'),

    -- SHAD - System Health and Administration Dashboard (2)
    ('shad.view-system-health-dashboard', 'View the system health dashboard showing platform performance and infrastructure metrics.'),
    ('shad.export-system-health-reports', 'Export reports and data from the system health dashboard.'),

    -- CRG - Custom Report Generation (7)
    ('crg.generate-custom-reports.build', 'Build a custom report using configurable filter criteria.'),
    ('crg.generate-custom-reports.run', 'Run a custom report and view its results.'),
    ('crg.schedule-automated-reports.create-schedule', 'Schedule a custom report for automated generation and delivery.'),
    ('crg.schedule-automated-reports.update-cancel-schedule', 'Update or cancel a scheduled automated report.'),
    ('crg.manage-report-templates.create', 'Create a reusable custom report template.'),
    ('crg.manage-report-templates.update', 'Update an existing custom report template.'),
    ('crg.manage-report-templates.delete', 'Delete a custom report template.')
) AS v(permission_code, description)
WHERE permissions.permission_code = v.permission_code
  AND permissions.tenant_id IS NULL;

UPDATE permissions
SET description = v.description
FROM (VALUES
    -- CRM - Records Retention & Compliance Management (7)
    ('crm.manage-retention-policies.create', 'Create a retention policy for a document type, department, or folder.'),
    ('crm.manage-retention-policies.update', 'Update an existing retention policy.'),
    ('crm.manage-retention-policies.delete', 'Delete a retention policy.'),
    ('crm.change-document-status.mark-active', 'Mark a document''s status as Active.'),
    ('crm.change-document-status.mark-inactive', 'Mark a document''s status as Inactive.'),
    ('crm.place-legal-hold', 'Place a legal hold on a document, suspending its archival and deletion.'),
    ('crm.lift-legal-hold', 'Lift a legal hold from a document, resuming normal retention processing.'),

    -- ABDR - Comprehensive Audit Management (5)
    ('abdr.view-audit-history', 'View the platform-wide audit trail across all modules.'),
    ('abdr.export-audit-reports', 'Export a platform-wide audit report.'),
    ('abdr.configure-audit-alert-rules.create', 'Create an alert rule for a high-risk audit event pattern.'),
    ('abdr.configure-audit-alert-rules.update', 'Update an existing audit alert rule.'),
    ('abdr.configure-audit-alert-rules.delete', 'Delete an audit alert rule.'),

    -- BRDR - Backup, Restoration and Disaster Recovery (7)
    ('brdr.manage-backup-configuration.configure-schedule', 'Configure the schedule for automated backups.'),
    ('brdr.manage-backup-configuration.configure-storage-location', 'Configure the storage location used for backups.'),
    ('brdr.manage-backup-configuration.view-job-status', 'View the status of backup jobs.'),
    ('brdr.initiate-restoration.single-document', 'Restore a single document from a backup.'),
    ('brdr.initiate-restoration.full-tenant', 'Restore a tenant''s full data set from a backup.'),
    ('brdr.execute-disaster-recovery-procedure.failover', 'Execute failover to the disaster recovery site.'),
    ('brdr.execute-disaster-recovery-procedure.failback', 'Execute failback from the disaster recovery site to production.'),

    -- RBR - Recycle Bin and Recovery (3)
    ('rbr.restore-deleted-documents', 'Restore a deleted document from the recycle bin.'),
    ('rbr.permanently-purge-documents', 'Permanently purge a document from the recycle bin.'),
    ('rbr.manage-recycle-bin-retention', 'Configure the retention period for items in the recycle bin.'),

    -- IAM - Third-Party Integration (11)
    ('iam.manage-api-access-keys.generate-key', 'Generate an API access key for a third-party integration.'),
    ('iam.manage-api-access-keys.revoke-key', 'Revoke an API access key.'),
    ('iam.manage-api-access-keys.configure-scopes-limits', 'Configure the scopes and rate limits for an API access key.'),
    ('iam.manage-webhook-subscriptions.create', 'Create a webhook subscription for a third-party integration.'),
    ('iam.manage-webhook-subscriptions.update', 'Update an existing webhook subscription.'),
    ('iam.manage-webhook-subscriptions.delete', 'Delete a webhook subscription.'),
    ('iam.manage-third-party-connectors.install-enable', 'Install or enable a third-party connector.'),
    ('iam.manage-third-party-connectors.configure', 'Configure a third-party connector.'),
    ('iam.manage-third-party-connectors.disable', 'Disable a third-party connector.'),
    ('iam.view-integration-activity-log.view', 'View the third-party integration activity log.'),
    ('iam.view-integration-activity-log.export', 'Export the third-party integration activity log.'),

    -- Legacy coarse permission codes (4) - reused verbatim from
    -- V8__backfill_role_permission_descriptions.sql
    ('role.view', 'View role definitions and their permission grants.'),
    ('role.manage', 'Create, update, and delete roles and manage their permission grants.'),
    ('user.view', 'View user profiles within the tenant.'),
    ('user.manage', 'Create, update, and deactivate users within the tenant.')
) AS v(permission_code, description)
WHERE permissions.permission_code = v.permission_code
  AND permissions.tenant_id IS NULL;

-- =====================================================================
-- Post-backfill sanity check: every permission row must now have a
-- non-null, non-empty description. Fails loudly if any of the 226 rows
-- was missed across the 6 batches above (e.g. a typo'd permission_code
-- that didn't match any row).
-- =====================================================================
DO $$
DECLARE
    missing_count INT;
BEGIN
    SELECT COUNT(*) INTO missing_count
    FROM permissions
    WHERE tenant_id IS NULL
      AND (description IS NULL OR btrim(description) = '');

    IF missing_count <> 0 THEN
        RAISE EXCEPTION 'Expected every shared-catalog permission to have a description after backfill, but % row(s) are still missing one', missing_count;
    END IF;
END $$;
