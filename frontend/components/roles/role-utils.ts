/**
 * Role names are stored underscore-separated with no spaces (enforced by
 * `roleFormSchema`'s validation in `schema.ts`) — Keycloak realm role
 * names are space-sensitive throughout the rest of the app (JwtAuthConverter's
 * `"ROLE_" + name.toUpperCase()` mapping, `hasRole(...)` checks), so
 * keeping the canonical name space-free avoids that whole class of
 * mismatch. This is purely a *display* transform for read-only surfaces
 * (tables, headings, badges, dropdown option labels) — never apply it to
 * a value before submitting a form or otherwise sending a name to the
 * backend, and never use its output to compare against a stored role name
 * (matching/lookups should always use the raw, underscored value).
 */
export function formatRoleNameForDisplay(name: string): string {
  return name.replace(/_/g, " ")
}
