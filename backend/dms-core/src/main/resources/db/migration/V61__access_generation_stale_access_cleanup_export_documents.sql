-- Folder/document access (V53/V54): a cache key, stale-entry cleanup, and
-- what each export contains.

-- 1. Access generation --------------------------------------------------------
--
-- FolderAccessService caches each tenant's folder tree and each user's access
-- entries between requests. The cache is keyed by the tenant's access
-- generation, which these triggers move on whenever anything that decides
-- access changes - so no code path can forget to invalidate it, and every
-- dms-core instance sees the change on its next request.
--
-- The value comes from a sequence, not generation + 1: a change that is
-- rolled back still burns its number, so a cache entry built inside that
-- transaction can never match a later, committed generation.

CREATE SEQUENCE access_generation_seq;

CREATE TABLE access_generations (
    tenant_id   UUID    PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    generation  BIGINT  NOT NULL
);

CREATE FUNCTION bump_access_generation() RETURNS trigger AS $$
DECLARE
    t UUID;
BEGIN
    t := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    IF t IS NULL THEN
        RETURN NULL; -- a platform-wide row (no tenant) decides no tenant's folder access
    END IF;
    INSERT INTO access_generations (tenant_id, generation) VALUES (t, nextval('access_generation_seq'))
    ON CONFLICT (tenant_id) DO UPDATE SET generation = excluded.generation;
    IF TG_OP = 'UPDATE' AND OLD.tenant_id IS NOT NULL AND OLD.tenant_id IS DISTINCT FROM NEW.tenant_id THEN
        INSERT INTO access_generations (tenant_id, generation) VALUES (OLD.tenant_id, nextval('access_generation_seq'))
        ON CONFLICT (tenant_id) DO UPDATE SET generation = excluded.generation;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Every change to these decides who sees which folder.
CREATE TRIGGER trg_folders_access_generation AFTER INSERT OR UPDATE OR DELETE ON folders
    FOR EACH ROW EXECUTE FUNCTION bump_access_generation();
CREATE TRIGGER trg_folder_access_generation AFTER INSERT OR UPDATE OR DELETE ON folder_access
    FOR EACH ROW EXECUTE FUNCTION bump_access_generation();
CREATE TRIGGER trg_document_access_generation AFTER INSERT OR UPDATE OR DELETE ON document_access
    FOR EACH ROW EXECUTE FUNCTION bump_access_generation();
CREATE TRIGGER trg_user_roles_access_generation AFTER INSERT OR UPDATE OR DELETE ON user_roles
    FOR EACH ROW EXECUTE FUNCTION bump_access_generation();
CREATE TRIGGER trg_roles_access_generation AFTER INSERT OR UPDATE OR DELETE ON roles
    FOR EACH ROW EXECUTE FUNCTION bump_access_generation();
-- Documents only matter when a restricted one comes, goes or changes mode -
-- an ordinary upload or metadata edit leaves the generation alone.
CREATE TRIGGER trg_documents_access_generation_insert AFTER INSERT ON documents
    FOR EACH ROW WHEN (NEW.access_mode = 'restricted') EXECUTE FUNCTION bump_access_generation();
CREATE TRIGGER trg_documents_access_generation_update AFTER UPDATE ON documents
    FOR EACH ROW WHEN (OLD.access_mode IS DISTINCT FROM NEW.access_mode
        OR ((OLD.access_mode = 'restricted' OR NEW.access_mode = 'restricted')
            AND OLD.is_deleted IS DISTINCT FROM NEW.is_deleted))
    EXECUTE FUNCTION bump_access_generation();
CREATE TRIGGER trg_documents_access_generation_delete AFTER DELETE ON documents
    FOR EACH ROW WHEN (OLD.access_mode = 'restricted') EXECUTE FUNCTION bump_access_generation();

-- 2. Stale access entries -----------------------------------------------------
--
-- principal_id isn't a foreign key (it names a user or a role), and folders
-- and documents are soft-deleted, so entries could outlive what they name:
-- a permanently deleted user, a deleted role, a deleted folder or document.
-- None of those can come back (a deactivated user can - their entries stay),
-- so their entries grant nothing and only clutter the access lists. Cleared
-- now, and from here on as soon as the thing they name goes.

DELETE FROM folder_access a WHERE a.principal_type = 'user'
    AND NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = a.principal_id AND u.tenant_id = a.tenant_id);
DELETE FROM folder_access a WHERE a.principal_type = 'role'
    AND NOT EXISTS (SELECT 1 FROM roles r WHERE r.role_id = a.principal_id AND r.tenant_id = a.tenant_id
                    AND NOT r.is_deleted);
DELETE FROM folder_access a USING folders f WHERE f.folder_id = a.folder_id AND f.is_deleted;
DELETE FROM document_access a WHERE a.principal_type = 'user'
    AND NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = a.principal_id AND u.tenant_id = a.tenant_id);
DELETE FROM document_access a WHERE a.principal_type = 'role'
    AND NOT EXISTS (SELECT 1 FROM roles r WHERE r.role_id = a.principal_id AND r.tenant_id = a.tenant_id
                    AND NOT r.is_deleted);
DELETE FROM document_access a USING documents d WHERE d.document_id = a.document_id AND d.is_deleted;

CREATE FUNCTION drop_access_entries_of_deleted_user() RETURNS trigger AS $$
BEGIN
    DELETE FROM folder_access WHERE tenant_id = OLD.tenant_id AND principal_type = 'user' AND principal_id = OLD.user_id;
    DELETE FROM document_access WHERE tenant_id = OLD.tenant_id AND principal_type = 'user' AND principal_id = OLD.user_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_users_drop_access_entries AFTER DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION drop_access_entries_of_deleted_user();

CREATE FUNCTION drop_access_entries_of_deleted_role() RETURNS trigger AS $$
BEGIN
    DELETE FROM folder_access WHERE tenant_id = OLD.tenant_id AND principal_type = 'role' AND principal_id = OLD.role_id;
    DELETE FROM document_access WHERE tenant_id = OLD.tenant_id AND principal_type = 'role' AND principal_id = OLD.role_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_roles_drop_access_entries_delete AFTER DELETE ON roles
    FOR EACH ROW EXECUTE FUNCTION drop_access_entries_of_deleted_role();
CREATE TRIGGER trg_roles_drop_access_entries_soft AFTER UPDATE OF is_deleted ON roles
    FOR EACH ROW WHEN (NEW.is_deleted AND NOT OLD.is_deleted) EXECUTE FUNCTION drop_access_entries_of_deleted_role();

CREATE FUNCTION drop_access_entries_of_deleted_folder() RETURNS trigger AS $$
BEGIN
    DELETE FROM folder_access WHERE tenant_id = NEW.tenant_id AND folder_id = NEW.folder_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_folders_drop_access_entries AFTER UPDATE OF is_deleted ON folders
    FOR EACH ROW WHEN (NEW.is_deleted AND NOT OLD.is_deleted) EXECUTE FUNCTION drop_access_entries_of_deleted_folder();

CREATE FUNCTION drop_access_entries_of_deleted_document() RETURNS trigger AS $$
BEGIN
    DELETE FROM document_access WHERE tenant_id = NEW.tenant_id AND document_id = NEW.document_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_documents_drop_access_entries AFTER UPDATE OF is_deleted ON documents
    FOR EACH ROW WHEN (NEW.is_deleted AND NOT OLD.is_deleted) EXECUTE FUNCTION drop_access_entries_of_deleted_document();

-- 3. What an export contains --------------------------------------------------
--
-- The documents a finished export holds, so its download can be refused once
-- the requester has lost access to any of them (or one was deleted) - the ZIP
-- must never outlive the access it was made with.

ALTER TABLE export_jobs ADD COLUMN document_ids UUID[];
