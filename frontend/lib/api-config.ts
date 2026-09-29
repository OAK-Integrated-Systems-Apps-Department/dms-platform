/**
 * Base URL of your Java backend (dms-core). Every page in app/ that used
 * to call a local /api/... route now calls `${API_BASE_URL}/api/v1/...`
 * directly instead — there is no Next.js server layer between the
 * browser and dms-core anymore.
 *
 * Must be NEXT_PUBLIC_-prefixed: these fetches run in the browser
 * ("use client" pages), not on a Next.js server, so only env vars
 * exposed at build time are visible here.
 *
 * An empty string is deliberate and valid (hence `??`, not `||`): it makes
 * every call a same-origin relative "/api/v1/..." request, which is what
 * the production image is built with - nginx proxies /api/ to dms-core
 * (see frontend/Dockerfile and docs/deployment.md). The localhost default
 * only applies to `bun dev` on a developer machine.
 */
export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"

/**
 * Base URL of Keycloak's own hosted Account Console
 * (`/realms/{realm}/account`), used by My Security Settings to deep-link
 * into Keycloak's native password/MFA management rather than DMS building
 * that UI itself — see the caveat on the S2 wireframe screen. Production
 * builds use the relative "/realms/oak-dms/account" (nginx proxies
 * /realms/ to Keycloak); the default below is for `bun dev` only.
 */
export const KEYCLOAK_ACCOUNT_URL =
  process.env.NEXT_PUBLIC_KEYCLOAK_ACCOUNT_URL ??
  "http://localhost:8081/realms/oak-dms/account"
