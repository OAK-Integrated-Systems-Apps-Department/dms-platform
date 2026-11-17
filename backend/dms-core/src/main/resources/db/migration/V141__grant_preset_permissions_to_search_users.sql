-- Document Search & Retrieval, Phase 2: anyone who can search can keep their own presets.
--
-- dsr.manage-saved-search-presets.{create,update,delete} were seeded in V10 and granted to nobody, so no one could save a
-- preset. Every role that holds dsr.search-documents (V139 gave it to every role that can view documents) gets the three
-- manage codes; administrators can still withdraw them per role. The .share code is deliberately NOT granted: sharing a
-- preset is declared but unused (presets are private, DSR-NFR-008).
-- NOT EXISTS rather than ON CONFLICT: resource_scope may be NULL (see V15, V87; same shape as V134 and V139).
WITH desired_grants AS (
    SELECT rp.tenant_id, rp.role_id, p_new.permission_id, rp.resource_scope
    FROM role_permissions rp
    JOIN permissions p_old ON p_old.permission_id = rp.permission_id
        AND p_old.permission_code = 'dsr.search-documents'
    CROSS JOIN permissions p_new
    WHERE p_new.permission_code IN ('dsr.manage-saved-search-presets.create',
                                    'dsr.manage-saved-search-presets.update',
                                    'dsr.manage-saved-search-presets.delete')
      AND p_new.tenant_id IS NULL
)
INSERT INTO role_permissions (tenant_id, role_id, permission_id, resource_scope)
SELECT dg.tenant_id, dg.role_id, dg.permission_id, dg.resource_scope
FROM desired_grants dg
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions existing
    WHERE existing.role_id = dg.role_id
      AND existing.permission_id = dg.permission_id
      AND existing.resource_scope IS NOT DISTINCT FROM dg.resource_scope
);
