package com.oak.dms.security.rbac.catalog;

/**
 * Single source of truth for the granular permission codes checked by
 * {@code @authz.hasPermission(...)} / {@code @authz.hasAnyPermission(...)}
 * across {@code UserController}, {@code UserRoleController},
 * {@code RoleController} and {@code RolePermissionController}. Each value
 * here must exactly match a {@code permission_code} row seeded by
 * {@code V10__seed_fine_grained_permissions.sql} - see
 * {@code V15__deprecate_legacy_permissions_and_backfill_granular.sql} for
 * how the 4 legacy coarse codes these replaced (user.view/user.manage/
 * role.view/role.manage) were deprecated and backfilled so no existing
 * role lost access when the checks below switched over.
 * <p>
 * {@code @PreAuthorize} is evaluated as Spring EL, which has no notion of a
 * Java import, so a constant here must be referenced through a
 * fully-qualified {@code T(...)} type reference rather than the plain
 * field name a normal Java caller would use:
 * <pre>{@code
 * @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).OTUA_USERS_CREATE)")
 * }</pre>
 * Not every permission in the catalog has a constant here - only the ones
 * actually referenced by an {@code @PreAuthorize} check.
 * <p>
 * {@code OTUA_USERS_ACTIVATE}/{@code OTUA_USERS_DEACTIVATE} replace a
 * single {@code otua.manage-users.activate-deactivate} code that used to
 * gate both directions together - split so a role can be granted one
 * without the other (e.g. an offboarding-only role that can deactivate
 * but never reactivate). See {@code V20__split_activate_deactivate_and_wire_delete_user.sql}.
 * {@code OTUA_USERS_DELETE} now also gates {@code UserController#purgeUser},
 * in addition to (not instead of) the tenant-admin bypass every check
 * here already gets via {@code AuthorizationService#hasFullAccess} - see
 * that method's own doc comment for the tradeoff this accepts.
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    // Users - OTUA (Organization, Tenant and User Administration) / Manage Users
    public static final String OTUA_USERS_CREATE = "otua.manage-users.create";
    public static final String OTUA_USERS_UPDATE = "otua.manage-users.update";
    public static final String OTUA_USERS_VIEW = "otua.manage-users.view";
    public static final String OTUA_USERS_RESET_PASSWORD = "otua.manage-users.reset-password";
    public static final String OTUA_USERS_ACTIVATE = "otua.manage-users.activate";
    public static final String OTUA_USERS_DEACTIVATE = "otua.manage-users.deactivate";
    public static final String OTUA_USERS_DELETE = "otua.manage-users.delete";
    public static final String OTUA_USERS_ASSIGN_ROLE = "otua.manage-users.assign-role";
    /** Gates {@code ImpersonationController#start} - see that class and {@code ImpersonationContextFilter} for the full "Act as User" mechanism. */
    public static final String OTUA_USERS_IMPERSONATE = "otua.manage-users.impersonate";

    // Pin Important Documents - PID (pins are personal; the limit is the organisation's)
    public static final String PID_PIN = "pid.pin-unpin-documents.pin";
    public static final String PID_UNPIN = "pid.pin-unpin-documents.unpin";
    public static final String PID_CONFIGURE_LIMITS = "pid.configure-pin-limits";

    // System monitoring - SHAD (System Health and Administration Dashboard).
    // Held implicitly by TENANT_ADMIN and PLATFORM_ADMIN like every code; others get them via a role.
    public static final String SHAD_VIEW_SYSTEM_HEALTH = "shad.view-system-health-dashboard";
    /** Log lines can carry anything a service printed, so reading them is a grant of its own. */
    public static final String SHAD_VIEW_SYSTEM_LOGS = "shad.view-system-logs";

    // Roles - ASM (Access and Security Management) / Manage Roles & Permissions
    public static final String ASM_ROLES_CREATE_ROLE = "asm.manage-roles-permissions.create-role";
    public static final String ASM_ROLES_UPDATE_ROLE = "asm.manage-roles-permissions.update-role";
    public static final String ASM_ROLES_DELETE_ROLE = "asm.manage-roles-permissions.delete-role";
    public static final String ASM_ROLES_VIEW_ROLE = "asm.manage-roles-permissions.view-role";
    public static final String ASM_ROLES_ASSIGN_PERMISSIONS = "asm.manage-roles-permissions.assign-permissions";

    // Metadata & Document-Type Management - MDTM (module 2.7)
    //
    // Extends the MDTM module/action rows already seeded by V6/V10 rather
    // than introducing new resource names: document types are
    // "manage-content-types" and metadata fields are "manage-metadata-schema"
    // in the DB catalog, even though the module 2.7 requirement doc talks
    // about "document types" and "metadata fields" - see V24 for the
    // additional view/activate-deactivate/deprecate-field/type-field-mapping
    // action rows this module needed that V10 never seeded (it only ever
    // seeded create/update/delete-shaped actions).
    public static final String MDTM_CONTENT_TYPES_CREATE = "mdtm.manage-content-types.create";
    public static final String MDTM_CONTENT_TYPES_UPDATE = "mdtm.manage-content-types.update";
    public static final String MDTM_CONTENT_TYPES_DELETE = "mdtm.manage-content-types.delete";
    public static final String MDTM_CONTENT_TYPES_VIEW = "mdtm.manage-content-types.view";
    public static final String MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE = "mdtm.manage-content-types.activate-deactivate";

    public static final String MDTM_METADATA_SCHEMA_CREATE_FIELD = "mdtm.manage-metadata-schema.create-field";
    public static final String MDTM_METADATA_SCHEMA_UPDATE_FIELD = "mdtm.manage-metadata-schema.update-field";
    public static final String MDTM_METADATA_SCHEMA_DELETE_FIELD = "mdtm.manage-metadata-schema.delete-field";
    public static final String MDTM_METADATA_SCHEMA_VIEW_FIELD = "mdtm.manage-metadata-schema.view-field";
    public static final String MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD = "mdtm.manage-metadata-schema.activate-deactivate-field";
    public static final String MDTM_METADATA_SCHEMA_DEPRECATE_FIELD = "mdtm.manage-metadata-schema.deprecate-field";
    public static final String MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES = "mdtm.manage-metadata-schema.set-validation-rules";

    public static final String MDTM_TYPE_FIELD_MAPPING_VIEW = "mdtm.manage-type-field-mapping.view";
    public static final String MDTM_TYPE_FIELD_MAPPING_UPDATE = "mdtm.manage-type-field-mapping.update";
    // Document templates (V70) - seeing and using them needs only docm.documents.view/create.
    public static final String MDTM_DOCUMENT_TEMPLATES_CREATE = "mdtm.manage-document-templates.create";
    public static final String MDTM_DOCUMENT_TEMPLATES_UPDATE = "mdtm.manage-document-templates.update";
    public static final String MDTM_DOCUMENT_TEMPLATES_DELETE = "mdtm.manage-document-templates.delete";

    // Documents - DOCM (Document Management) - see V33's seed migration.
    public static final String DOCM_DOCUMENTS_CREATE = "docm.documents.create";
    public static final String DOCM_DOCUMENTS_VIEW = "docm.documents.view";
    public static final String DOCM_DOCUMENTS_DELETE = "docm.documents.delete";
    // Filing documents into folders / moving them between folders (V51).
    public static final String DOCM_DOCUMENTS_MOVE = "docm.documents.move";

    // Version history and the recycle bin - seeded by V10.
    public static final String DVC_VERSIONS_VIEW = "dvc.view-version-history.view";
    public static final String DVC_VERSIONS_RESTORE = "dvc.restore-prior-version";
    public static final String RBR_RESTORE = "rbr.restore-deleted-documents";
    public static final String RBR_PURGE = "rbr.permanently-purge-documents";
    public static final String RBR_RETENTION = "rbr.manage-recycle-bin-retention";

    // Folders - DSC (Document Storage & Categorization) / Manage Folder
    // Structure, seeded by V10. Browsing the tree needs DOCM_DOCUMENTS_VIEW.
    public static final String DSC_FOLDERS_CREATE = "dsc.manage-folder-structure.create-folder";
    public static final String DSC_FOLDERS_RENAME_MOVE = "dsc.manage-folder-structure.rename-move-folder";
    public static final String DSC_FOLDERS_DELETE = "dsc.manage-folder-structure.delete-folder";
    // Folder access control (V10 codes, used from V53): who may get into a folder.
    public static final String DSC_FOLDER_ACCESS_ASSIGN = "dsc.configure-folder-access-control.assign-permissions";
    public static final String DSC_FOLDER_ACCESS_VIEW = "dsc.configure-folder-access-control.view-access-list";
    // Encryption at rest (V10 codes, used from V55).
    public static final String DE_KEYS_VIEW = "de.manage-encryption-keys.view-key-metadata";
    public static final String DE_KEYS_ROTATE = "de.manage-encryption-keys.rotate-key";
    public static final String DE_KEYS_GENERATE = "de.manage-encryption-keys.generate-key";

    // Writing document comments (V50). Reading them needs DOCM_DOCUMENTS_VIEW.
    public static final String DOCM_COMMENTS_CREATE = "docm.document-comments.create";
    // Quarantine review (V40) - granted to no role by default.
    // DOCM_QUARANTINE_OVERRIDE is deliberately separate from _RELEASE: it
    // force-releases a file the scanner still flags.
    public static final String DOCM_QUARANTINE_VIEW = "docm.quarantine.view";
    public static final String DOCM_QUARANTINE_RELEASE = "docm.quarantine.release";
    public static final String DOCM_QUARANTINE_DELETE = "docm.quarantine.delete";
    public static final String DOCM_QUARANTINE_OVERRIDE = "docm.quarantine.override";

    // File Import/Export - FIE. Seeded by V10; the unused codes are
    // deprecated by V49. Bulk import is gated as a whole on upload-file
    // (using a saved template included), bulk export on execute-export, and
    // the tenant's saved import templates on manage-import-templates.*.
    public static final String FIE_BULK_IMPORT_UPLOAD_FILE = "fie.bulk-import-documents.upload-file";
    public static final String FIE_BULK_EXPORT_EXECUTE = "fie.bulk-export-documents.execute-export";
    public static final String FIE_IMPORT_TEMPLATES_CREATE = "fie.manage-import-templates.create-template";
    public static final String FIE_IMPORT_TEMPLATES_UPDATE = "fie.manage-import-templates.update-template";
    public static final String FIE_IMPORT_TEMPLATES_DELETE = "fie.manage-import-templates.delete-template";


    // Audit - ABDR (Comprehensive Audit Management) / View Audit History
    public static final String ABDR_VIEW_AUDIT_HISTORY = "abdr.view-audit-history";
    // Distinct from ABDR_VIEW_AUDIT_HISTORY on purpose - exporting the trail
    // (a bulk copy leaving the system) is a stricter capability than
    // viewing it on-screen, and the seeded catalog (V10) already reserves a
    // separate code for it rather than folding it into "view".
    public static final String ABDR_EXPORT_AUDIT_REPORTS = "abdr.export-audit-reports";

    // Document Watermarking - DW. Both codes were seeded by V10 and the
    // module by V7, long before any Java referenced them. Phase 1 uses only
    // these two: APPLYING a watermark is gated on DOCM_DOCUMENTS_VIEW (plus
    // the folder's own view level, and DVC_VERSIONS_VIEW when the request
    // names a specific version), because a watermarked copy shows the
    // person exactly what an ordinary download already would.
    // The catalogue has no delete-template code and this deliberately
    // doesn't add one - deleting reuses UPDATE, see
    // WatermarkTemplateService#delete for the reasoning.
    public static final String DW_WATERMARK_TEMPLATE_CREATE = "dw.configure-watermark-policy.create-template";
    public static final String DW_WATERMARK_TEMPLATE_UPDATE = "dw.configure-watermark-policy.update-template";

    // OCR & Scanning (module OS). These codes are NOT new - V10 has seeded all
    // seven since the fine-grained catalogue landed, and the Roles screen has
    // always listed them. What was missing was anything using them: the OCR
    // branch shipped its routes with no permission check at all. No migration
    // is needed to start enforcing them, only these constants.
    //
    // Note they are granted to no role by default. TENANT_ADMIN and
    // PLATFORM_ADMIN hold every permission implicitly, so an administrator sees
    // the OCR screens immediately; everybody else needs an explicit grant,
    // which is the intended default for a capability that reads document
    // content.
    public static final String OS_OCR_TRIGGER = "os.perform-ocr-extraction.trigger";
    public static final String OS_OCR_EDIT_TEXT = "os.perform-ocr-extraction.edit-text";
    public static final String OS_OCR_TEMPLATE_CREATE = "os.configure-ocr-zones-templates.create-template";
    public static final String OS_OCR_TEMPLATE_UPDATE = "os.configure-ocr-zones-templates.update-template";
    public static final String OS_OCR_TEMPLATE_DELETE = "os.configure-ocr-zones-templates.delete-template";
    public static final String OS_REVIEW_VIEW_QUEUE = "os.review-ocr-exceptions.view-queue";
    public static final String OS_REVIEW_RESOLVE = "os.review-ocr-exceptions.resolve";

    // Departments - OTUA, seeded by V46. Back the department approver pools
    // of workflow stages (3.1 Workflow Automation).
    public static final String OTUA_DEPARTMENTS_VIEW = "otua.manage-departments.view";
    public static final String OTUA_DEPARTMENTS_CREATE = "otua.manage-departments.create";
    public static final String OTUA_DEPARTMENTS_UPDATE = "otua.manage-departments.update";
    public static final String OTUA_DEPARTMENTS_DELETE = "otua.manage-departments.delete";

    // Workflow Automation - WA, seeded by V10. toggle-active gates activating
    // and deactivating a template version.
    public static final String WA_TEMPLATES_CREATE = "wa.configure-workflow-templates.create";
    public static final String WA_TEMPLATES_UPDATE = "wa.configure-workflow-templates.update";
    public static final String WA_TEMPLATES_DELETE = "wa.configure-workflow-templates.delete";
    public static final String WA_TEMPLATES_TOGGLE_ACTIVE = "wa.configure-workflow-templates.toggle-active";
    public static final String WA_TASK_APPROVE = "wa.approve-reject-workflow-task.approve";
    public static final String WA_TASK_REJECT = "wa.approve-reject-workflow-task.reject";
    // An admin moves any open task to another user (Phase 2); holders are
    // also mailed when a stage has no eligible approver or an escalation
    // target can't be used.
    public static final String WA_TASK_REASSIGN = "wa.reassign-workflow-task";
    // The holder of a task pauses it to ask someone for information (V50).
    public static final String WA_REQUEST_INFORMATION = "wa.request-workflow-information.create";
    // Lets a non-participant (e.g. a compliance reviewer) see any instance's
    // status and timeline.
    public static final String WA_AUDIT_TRAIL_VIEW = "wa.view-workflow-audit-trail.view";

    // Document Lifecycle - DLM, seeded by V10. Submitting a document into its
    // document type's active workflow.
    public static final String DLM_SUBMIT_DOCUMENTS_FOR_REVIEW = "dlm.submit-documents-for-review";

    // Notifications - NOTIF (3.2), seeded by V105. Granted to no role by
    // default; tenant admins hold them implicitly. Own inbox and own
    // preferences gate on identity, not a permission.
    public static final String NOTIF_SETTINGS_MANAGE = "notif.notification-settings.manage";
    public static final String NOTIF_DELIVERY_LOG_VIEW = "notif.delivery-log.view";
    public static final String NOTIF_DELIVERY_LOG_RETRY = "notif.delivery-log.retry";

    // Document Relationships Management - DRM, seeded by V10. Edit reuses the
    // create code (D1); view-graph is seeded but unused - there is no graph view.
    public static final String DRM_RELATIONSHIPS_CREATE = "drm.manage-document-relationships.create";
    public static final String DRM_RELATIONSHIPS_REMOVE = "drm.manage-document-relationships.remove";
    public static final String DRM_RELATIONSHIPS_VIEW_GRAPH = "drm.manage-document-relationships.view-graph";
    public static final String DRM_TYPES_CREATE = "drm.configure-relationship-types.create-type";
    public static final String DRM_TYPES_UPDATE = "drm.configure-relationship-types.update-type";
    public static final String DRM_TYPES_DELETE = "drm.configure-relationship-types.delete-type";

    // Document Ownership and Transfer - DOT, seeded by V10. Single transfer = initiate-transfer; the
    // owned-documents list and offboarding preview = select-documents; bulk transfer and offboarding =
    // execute-transfer. approve-transfer is seeded but unused - there is no approval step (D2/D15).
    public static final String DOT_TRANSFER_INITIATE = "dot.transfer-document-ownership.initiate-transfer";
    public static final String DOT_TRANSFER_APPROVE = "dot.transfer-document-ownership.approve-transfer";
    public static final String DOT_BULK_SELECT = "dot.bulk-transfer-ownership.select-documents";
    public static final String DOT_BULK_EXECUTE = "dot.bulk-transfer-ownership.execute-transfer";
    public static final String DOT_HISTORY_VIEW = "dot.view-ownership-history.view";
    public static final String DOT_HISTORY_EXPORT = "dot.view-ownership-history.export";
}
