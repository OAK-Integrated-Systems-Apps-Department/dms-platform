// app/error/page.tsx
import { Suspense } from "react"

import { ErrorContent } from "./error-content"

/**
 * The one generic, no-detail error screen — for things like an expired or
 * already-used action-token link (see app/update-password/page.tsx) or any
 * other failure that isn't tied to a specific account. Deliberately NOT
 * used for login failures: per the original design note, account-level
 * denials (wrong password, disabled, locked) stay as an inline alert on
 * the login form itself (app/login/page.tsx) precisely so this page never
 * has to hint at account-specific detail.
 *
 * Public (see PUBLIC_PATHS in proxy.ts) — a person can land here with
 * no session, e.g. from a stale emailed link.
 */
export default function ErrorPage() {
  return (
    <Suspense fallback={null}>
      <ErrorContent />
    </Suspense>
  )
}