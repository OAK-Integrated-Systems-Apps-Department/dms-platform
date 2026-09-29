"use client"

import { useRouter } from "next/navigation"
import { Layers, ShieldOff } from "lucide-react"

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

      <div className="relative z-10 w-full max-w-[404px] rounded-xl border border-border bg-card p-9 pb-[34px] text-center shadow-[var(--ledger-shadow)]">
        <div className="mb-[30px] flex flex-col items-center">
          <div className="mb-3 flex size-[46px] items-center justify-center rounded-xl bg-primary shadow-[0_4px_14px_color-mix(in_srgb,var(--primary)_28%,transparent)]">
            <Layers size={22} color="var(--primary-foreground)" />
          </div>
          <div className="font-heading text-[19px] font-semibold tracking-[-0.01em] text-foreground">
            {APP_NAME}
          </div>
          <div className="mt-[5px] font-mono text-[9.5px] tracking-[0.16em] text-[var(--ink-faint)]">
            DOCUMENT VAULT
          </div>
        </div>

        <div className="mx-auto mb-5 flex size-14 items-center justify-center rounded-full border border-[color-mix(in_srgb,var(--gold)_40%,transparent)] bg-[var(--gold-tint)]">
          <ShieldOff size={26} className="text-[var(--gold)]" />
        </div>

        <h1 className="mb-3 font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
          Your session has expired
        </h1>
        <p className="mb-[26px] text-[13.5px] leading-[1.65] text-muted-foreground">
          For your security, you&apos;ve been signed out after a period of inactivity. Sign in again to
          continue.
        </p>

        <button
          type="button"
          onClick={handleSignInAgain}
          className="w-full rounded-md border border-[var(--primary-hover)] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[var(--primary-hover)]"
        >
          Sign in again
        </button>
      </div>
    </div>
  )
}