-- Seeds the "Act as User" impersonation permission under the same "Manage
-- Users" action every other otua.manage-users.* permission already lives
-- under - same additive pattern as V20/V21 (new permission joined onto an
-- existing permission's action_id, no new permission_actions row needed).

INSERT INTO permissions (permission_code, name, action_id)
SELECT 'otua.manage-users.impersonate', 'Act as user (impersonate)', existing.action_id
FROM (
    SELECT action_id FROM permissions
    WHERE permission_code = 'otua.manage-users.view' AND tenant_id IS NULL
) AS existing;
