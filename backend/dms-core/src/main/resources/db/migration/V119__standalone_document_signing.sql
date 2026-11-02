-- 1.4 Digital Signature Integration - lets a user add an e-signature (visual
-- mark + name/designation/date, cryptographically signed) directly to a
-- document, independent of approving any workflow task. Mirrors the
-- existing task-scoped signature-placement/sign pair, but keyed off the
-- document itself instead of a workflow task/instance:
--
--   documents.standalone_signature_base_version_id  the version to revert to
--                         on "remove signature" - set the first time a
--                         standalone signing action (add page or place a
--                         mark) touches this document, cleared back to null
--                         once fully removed, mirroring
--                         workflow_tasks.signature_placement_base_version_id
--                         (V111) but reusable across many signing sessions
--                         rather than pinned for one task's whole lifetime.
--   document_signatures.workflow_instance_id  now nullable - a standalone
--                         signature has no workflow instance or task behind
--                         it (both columns null on that row); a
--                         task-produced signature still always sets both.

ALTER TABLE document_signatures ALTER COLUMN workflow_instance_id DROP NOT NULL;

ALTER TABLE documents
    ADD COLUMN standalone_signature_base_version_id UUID REFERENCES document_versions (version_id);

-- =====================================================================
-- Permission: dsi.sign-documents.sign-standalone - a new child permission
-- under the existing "Sign Documents" action (module DSI, seeded by V6/V7),
-- same pattern V10 used for dsi.sign-documents.sign itself. Distinct from
-- that permission (and from the dual WA_TASK_APPROVE+DSI_SIGN gate on
-- task-scoped placement) so granting "approve workflow tasks with a
-- signature" never implicitly grants "sign any document you can edit, any
-- time", and vice versa.
-- =====================================================================
DO
$$
DECLARE
    n bigint;
BEGIN
    WITH inserted AS (
        INSERT INTO permissions (permission_code, name, action_id)
        SELECT v.permission_code, v.name, pa.action_id
        FROM (VALUES
            ('dsi.sign-documents.sign-standalone', 'Sign a document directly, outside of workflow approval')
        ) AS v(permission_code, name)
        JOIN permission_actions pa ON pa.action_name = 'Sign Documents'
        JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'DSI'
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V118: % digital-signature permission(s) seeded (granted to no role)', n;
END;
$$;
