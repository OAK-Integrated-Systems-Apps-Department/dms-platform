/**
 * Best-effort fallback for turning a raw machine code (e.g. "VIEW",
 * "MANAGE_ROLES_AND_PERMISSIONS") into something readable, for the rare
 * case a module/action's `moduleName`/`actionName` comes back empty from
 * the backend. `permission_modules.module_name` and
 * `permission_actions.action_name` are NOT NULL columns, always seeded
 * alongside their code (see `V5__create_permission_modules_and_actions.sql`
 * and `V6__seed_permission_modules_and_actions.sql`), so this should never
 * actually trigger in practice — it exists purely so a missing/blank name
 * degrades to a readable label instead of blank text or "undefined".
 */
export function humanizeCode(code: string): string {
  return code
    .toLowerCase()
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(" ")
}
