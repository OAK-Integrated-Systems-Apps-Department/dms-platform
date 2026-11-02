-- Decision 7 is narrowed, not removed: a document type may now have more
-- than one active template of DIFFERENT codes at a time (the submitter picks
-- which one to use when more than one applies - see TemplateSelector /
-- WorkflowSubmissionService). What still holds is "at most one active
-- version per template family" (same code) - that's what versioning
-- (draft -> activate -> new version) assumes, and WorkflowInstance already
-- snapshots templateId/templateVersionNumber at submission either way, so
-- in-flight instances are unaffected by any of this.
DROP INDEX uq_workflow_templates_one_active_per_type;

CREATE UNIQUE INDEX uq_workflow_templates_one_active_per_code
    ON workflow_templates (tenant_id, code) WHERE status = 'active' AND is_deleted = false;
