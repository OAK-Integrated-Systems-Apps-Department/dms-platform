-- Manual rollback ("down") for V114__seed_relationship_types_for_existing_tenants.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Removes the system types
-- that nothing references; a type that already has relationships stays (drop V113's
-- tables to remove those).
DELETE FROM relationship_types rt
WHERE rt.is_system
  AND NOT EXISTS (SELECT 1 FROM document_relationships r
                  WHERE r.tenant_id = rt.tenant_id AND r.relationship_type_id = rt.relationship_type_id);
