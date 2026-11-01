// The product name shown across the UI. Set NEXT_PUBLIC_APP_NAME (inlined at
// build time; infra passes it from APP_NAME in the stack's .env) to rename it -
// the backend's emails/API docs use the same APP_NAME via PRODUCT_NAME.
export const APP_NAME = process.env.NEXT_PUBLIC_APP_NAME || "OAK iDocs"

// Shared route path used by 3+ of the standalone auth screens (login,
// error, session-expired, login-timeout, update-password) to link/redirect
// back to sign-in — kept here so those screens don't each hardcode it.
export const ROUTES = {
  LOGIN: "/login",
} as const
