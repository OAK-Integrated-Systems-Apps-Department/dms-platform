/**
 * Role names are stored underscore-separated with no spaces — Keycloak
 * realm role names are space-sensitive throughout the rest of the app
 * (JwtAuthConverter's `"ROLE_" + name.toUpperCase()` mapping, `hasRole(...)`
 * checks), so keeping the canonical name space-free avoids that whole
 * class of mismatch. The Add/Edit Role form never shows this underscored
 * form to the user, though — see `toBackendRoleName` below for the
 * corresponding space→underscore conversion applied on submit. This
 * function is purely a *display* transform for read-only surfaces
 * (tables, headings, badges, dropdown option labels) — never apply it to
 * a value before submitting a form or otherwise sending a name to the
 * backend, and never use its output to compare against a stored role name
 * (matching/lookups should always use the raw, underscored value).
 */
export function formatRoleNameForDisplay(name: string): string {
  return name.replace(/_/g, " ")
}

/**
 * Inverse of `formatRoleNameForDisplay` — converts a space-form name (what
 * the user types/sees in the Add/Edit Role form) back to the underscored
 * form the backend stores and Keycloak expects. Apply this only right
 * before sending a name to the backend (`roleName` in the create/update
 * payload) — never for display, and never to a value already underscored.
 */
export function toBackendRoleName(name: string): string {
  return name.trim().replace(/\s+/g, "_")
}
