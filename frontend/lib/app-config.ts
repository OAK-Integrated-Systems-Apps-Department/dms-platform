export const APP_NAME = "Ledger"

// Shared route path used by 3+ of the standalone auth screens (login,
// error, session-expired, login-timeout, update-password) to link/redirect
// back to sign-in — kept here so those screens don't each hardcode it.
export const ROUTES = {
  LOGIN: "/login",
} as const
