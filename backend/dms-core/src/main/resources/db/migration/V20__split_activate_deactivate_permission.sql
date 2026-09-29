-- Splits the combined "Activate / deactivate user" permission
-- (otua.manage-users.activate-deactivate) into two independently-
-- grantable permissions, so a role can be granted one direction without
-- the other (e.g. an offboarding-only role that can deactivate accounts
-- but never reactivate one). Both new permissions are seeded under the
-- same action ("Manage Users") the combined one already lived under.
--
-- otua.manage-users.delete already existed in the catalog and needs no
-- schema change here - only its @PreAuthorize wiring changed, in
-- UserController.
--
-- No backfill needed (contrast V15, which had to preserve existing
-- grants when it retired 4 legacy codes): confirmed against
-- role_permissions before writing this that no role, in any tenant,
-- currently holds otua.manage-users.activate-deactivate.

INSERT INTO permissions (permission_code, name, action_id)
SELECT v.permission_code, v.name, combined.action_id
FROM (VALUES
    ('otua.manage-users.activate', 'Activate user'),
    ('otua.manage-users.deactivate', 'Deactivate user')
) AS v(permission_code, name)
CROSS JOIN (
    SELECT action_id FROM permissions
    WHERE permission_code = 'otua.manage-users.activate-deactivate' AND tenant_id IS NULL
) AS combined;

UPDATE permissions
SET is_deprecated = true
WHERE permission_code = 'otua.manage-users.activate-deactivate'
  AND tenant_id IS NULL;
