-- Backs the User List's Delete action (UserServiceImpl#purgeUser), which
-- does a genuine hard delete (DELETE FROM users), not another flavor of
-- the existing soft-delete deactivateUser already does. Every FK that
-- points at users.user_id needs an explicit ON DELETE behavior for that
-- to work at all - without one, Postgres rejects the DELETE outright the
-- moment the user has ever created/updated anything, or holds a role, or
-- has an outstanding password reset token.
--
-- Two different behaviors, depending on what the referencing row means:
--
--  - created_by/updated_by columns (users, tenants, roles, permissions,
--    permission_modules, permission_actions) are just an audit trail on a
--    row that has nothing to do with this delete - ON DELETE SET NULL, so
--    purging who created/updated something doesn't take that something
--    down with it.
--
--  - user_roles and password_reset_tokens rows exist only to describe
--    THIS user (their role assignments, their own reset tokens) - ON
--    DELETE CASCADE, since once the user is gone those rows have nothing
--    left to describe.
--
-- Constraint names below are Postgres's own default
-- (<table>_<column>_fkey) for the ones that were never given an explicit
-- name in V1/V4/V5; the users/tenants ones use the fk_* names V1 gave
-- them itself.

ALTER TABLE users DROP CONSTRAINT fk_users_created_by;
ALTER TABLE users
    ADD CONSTRAINT fk_users_created_by FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE users DROP CONSTRAINT fk_users_updated_by;
ALTER TABLE users
    ADD CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE tenants DROP CONSTRAINT fk_tenants_created_by;
ALTER TABLE tenants
    ADD CONSTRAINT fk_tenants_created_by FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE tenants DROP CONSTRAINT fk_tenants_updated_by;
ALTER TABLE tenants
    ADD CONSTRAINT fk_tenants_updated_by FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE roles DROP CONSTRAINT roles_created_by_fkey;
ALTER TABLE roles
    ADD CONSTRAINT roles_created_by_fkey FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE roles DROP CONSTRAINT roles_updated_by_fkey;
ALTER TABLE roles
    ADD CONSTRAINT roles_updated_by_fkey FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE permissions DROP CONSTRAINT permissions_created_by_fkey;
ALTER TABLE permissions
    ADD CONSTRAINT permissions_created_by_fkey FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE permissions DROP CONSTRAINT permissions_updated_by_fkey;
ALTER TABLE permissions
    ADD CONSTRAINT permissions_updated_by_fkey FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE permission_modules DROP CONSTRAINT permission_modules_created_by_fkey;
ALTER TABLE permission_modules
    ADD CONSTRAINT permission_modules_created_by_fkey FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE permission_modules DROP CONSTRAINT permission_modules_updated_by_fkey;
ALTER TABLE permission_modules
    ADD CONSTRAINT permission_modules_updated_by_fkey FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE permission_actions DROP CONSTRAINT permission_actions_created_by_fkey;
ALTER TABLE permission_actions
    ADD CONSTRAINT permission_actions_created_by_fkey FOREIGN KEY (created_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE permission_actions DROP CONSTRAINT permission_actions_updated_by_fkey;
ALTER TABLE permission_actions
    ADD CONSTRAINT permission_actions_updated_by_fkey FOREIGN KEY (updated_by)
        REFERENCES users (user_id) ON DELETE SET NULL;

ALTER TABLE user_roles DROP CONSTRAINT user_roles_user_id_fkey;
ALTER TABLE user_roles
    ADD CONSTRAINT user_roles_user_id_fkey FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE;

ALTER TABLE password_reset_tokens DROP CONSTRAINT password_reset_tokens_user_id_fkey;
ALTER TABLE password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_user_id_fkey FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE;