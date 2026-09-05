/**
 * Single source of truth for the app's display name. Used in the sidebar
 * header (`components/layout/app-sidebar.tsx`) and appended to every
 * browser tab title (`lib/use-page-title.ts`). Change it here and both
 * places pick it up — nothing else in the app should hardcode "Ledger".
 */
export const APP_NAME = "Ledger"
