-- Document Search & Retrieval: nobody loses search they already had.
--
-- The top-bar search needs only docm.documents.view; the new /search page and API need dsr.search-documents
-- (seeded in V10, never granted to anyone until now). Every role that already holds docm.documents.view gets it,
-- so "anyone who can view documents can search them" holds from day one; admins can still withdraw it per role.
-- NOT EXISTS rather than ON CONFLICT: resource_scope may be NULL, which a unique constraint never treats as equal
-- (see V15, V87; same shape as V134).
WITH desired_grants AS (
    SELECT rp.tenant_id, rp.role_id, p_new.permission_id, rp.resource_scope
    FROM role_permissions rp
    JOIN permissions p_old ON p_old.permission_id = rp.permission_id
        AND p_old.permission_code = 'docm.documents.view'
    CROSS JOIN permissions p_new
    WHERE p_new.permission_code = 'dsr.search-documents'
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
