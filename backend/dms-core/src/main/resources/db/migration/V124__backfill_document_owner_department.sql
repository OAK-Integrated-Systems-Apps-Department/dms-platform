-- DOT: existing documents take their owner's current department (set-based, idempotent).
UPDATE documents d SET owner_department_id = u.department_id
  FROM users u
 WHERE u.user_id = d.owner_user_id AND u.tenant_id = d.tenant_id
   AND u.department_id IS NOT NULL AND d.owner_department_id IS NULL;
