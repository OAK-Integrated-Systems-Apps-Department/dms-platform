/**
 * Base URL of your Java backend (dms-core). Every page in app/ that used
 * to call a local /api/... route now calls `${API_BASE_URL}/api/v1/...`
 * directly instead — there is no Next.js server layer between the
 * browser and dms-core anymore.
 *
 * Must be NEXT_PUBLIC_-prefixed: these fetches run in the browser
 * ("use client" pages), not on a Next.js server, so only env vars
 * exposed at build time are visible here.
 */
export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"

/**
 * Base URL of Keycloak's own hosted Account Console
 * (`/realms/{realm}/account`), used by My Security Settings to deep-link
 * into Keycloak's native password/MFA management rather than DMS building
 * that UI itself — see the caveat on the S2 wireframe screen. Not yet
 * configured on the backend, so this is a placeholder host.
 */
export const KEYCLOAK_ACCOUNT_URL =
  process.env.NEXT_PUBLIC_KEYCLOAK_ACCOUNT_URL ??
  "http://localhost:8081/realms/dms/account"
