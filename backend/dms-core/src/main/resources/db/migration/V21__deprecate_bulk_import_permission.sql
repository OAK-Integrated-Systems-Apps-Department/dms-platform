-- otua.manage-users.bulk-import has been in the catalog since V10 but was
-- never checked by any @PreAuthorize - no bulk-import endpoint exists
-- anywhere in the codebase. Granting it to a custom role was a pure no-op
-- an admin had no way to discover: the Permission Catalog let them check
-- the box believing it enabled a capability it never did.
--
-- Deprecating it (same mechanism V15 used for the 4 legacy coarse codes,
-- and V20 used for the combined activate-deactivate code) hides it from
-- new-role permission pickers - see Permission.deprecated and
-- use-permission-catalog.ts's buildPermissionGroups, which both already
-- filter on this flag - without deleting the row, so a role that
-- currently holds it (none do, confirmed against role_permissions before
-- writing this) wouldn't lose a visible grant, and the code can still be
-- re-enabled by clearing this flag if bulk import is ever actually built.

UPDATE permissions
SET is_deprecated = true
WHERE permission_code = 'otua.manage-users.bulk-import'
  AND tenant_id IS NULL;
