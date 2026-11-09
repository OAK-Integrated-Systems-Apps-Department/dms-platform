"use client"

import { useRouter } from "next/navigation"
import { ShieldOff } from "lucide-react"
import { BrandLogo } from "@/components/brand/brand-logo"

import { APP_NAME, ROUTES } from "@/lib/app-config"

/**
 * Distinct from /login-timeout: that one is "your attempt to log in took
 * too long," this one is "you WERE logged in, and the session lapsed from
 * inactivity." Same idea as /error vs account-specific denials — separate
 * screens for causes with genuinely different meaning, even though the
 * fix (go to /login) is identical.
 *
 * This is also the natural redirect target for a 401 from any
 * authenticated fetch elsewhere in the app (dashboard data calls,
 * app-topbar.tsx, etc.) once that wiring exists — not implemented yet,
 * same "frontend only for now" scope as the rest of these screens.
 *
 * Public (see PUBLIC_PATHS in proxy.ts) since the whole point is
 * there's no valid session by the time someone lands here.
 *
 * As of `components/providers/providers.tsx`'s global `QueryCache`/
 * `MutationCache` `onError`, the "not implemented yet" note above no
 * longer applies — a 401 from any authenticated call now redirects here
 * automatically (toast + this page), not just a manually-typed URL.
 */
export default function SessionExpiredPage() {
  const router = useRouter()

  function handleSignInAgain() {
    router.push(ROUTES.LOGIN)
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-background px-5 py-10">
      <div
        className="pointer-events-none fixed inset-0 z-0"
        style={{
          backgroundImage:
            "repeating-linear-gradient(to bottom, transparent, transparent 38px, color-mix(in srgb, var(--primary) 3.5%, transparent) 39px)",
        }}
      />

      <div className="relative z-10 w-full max-w-[404px] rounded-lg border border-border bg-card p-9 pb-[34px] text-center shadow-[var(--ledger-shadow)]">
        <div className="mb-[30px] flex flex-col items-center">
          <BrandLogo alt={APP_NAME} className="h-14" />
        </div>

        <div className="mx-auto mb-5 flex size-14 items-center justify-center rounded-full border border-[color-mix(in_srgb,var(--gold)_40%,transparent)] bg-[var(--gold-tint)]">
          <ShieldOff size={26} className="text-[var(--gold)]" />
        </div>

        <h1 className="mb-3 font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
          Your session has expired
        </h1>
        <p className="mb-[26px] text-[13.5px] leading-[1.65] text-muted-foreground">
          For your security, you&apos;ve been signed out after a period of
          inactivity. Sign in again to continue.
        </p>

        <button
          type="button"
          onClick={handleSignInAgain}
          className="h-[38px] w-full rounded-btn border border-[var(--primary-hover)] bg-primary text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[var(--primary-hover)]"
        >
          Sign in again
        </button>
      </div>
    </div>
  )
}
