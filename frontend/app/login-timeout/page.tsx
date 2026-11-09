"use client"

import { useRouter } from "next/navigation"
import { Clock } from "lucide-react"
import { BrandLogo } from "@/components/brand/brand-logo"

import { APP_NAME, ROUTES } from "@/lib/app-config"

/**
 * Shown when a login attempt has been sitting too long and Keycloak's
 * underlying authentication session has expired — distinct from the
 * generic /error page (that one's for unrecoverable/unexpected failures;
 * this one has a specific, actionable cause and a specific fix). Public
 * (see PUBLIC_PATHS in proxy.ts) since there's no session at this
 * point by definition.
 */
export default function LoginTimeoutPage() {
  const router = useRouter()

  function handleRestart() {
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
          <Clock size={26} className="text-[var(--gold)]" />
        </div>

        <h1 className="mb-3 font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
          Your login attempt timed out
        </h1>
        <p className="mb-[26px] text-[13.5px] leading-[1.65] text-muted-foreground">
          Restart the login process from the application you started from.
        </p>

        <button
          type="button"
          onClick={handleRestart}
          className="h-[38px] w-full rounded-btn border border-[var(--primary-hover)] bg-primary text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[var(--primary-hover)]"
        >
          Restart login
        </button>
      </div>
    </div>
  )
}
