-- 1.4 Digital Signature Integration - a signature placed on a document now
-- stamps the signer's name, position (job title), department and the
-- date/time alongside their visual mark (see SignaturePlacementService).
-- Name/department already exist (users.display_name, users.department_id ->
-- departments.name); position (job title) didn't, so it's added here.
-- Nullable: existing users have none set until they fill it in themselves
-- (PUT /api/v1/users/me) - the stamp simply omits that line when it's null.
ALTER TABLE users ADD COLUMN position VARCHAR(150);
