"use client"

import { useRouter } from "next/navigation"
import { Clock, Layers } from "lucide-react"

import { APP_NAME } from "@/lib/app-config"

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
    router.push("/login")
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-background px-5 py-10">
      <div
        className="pointer-events-none fixed inset-0 z-0"
        style={{
          backgroundImage:
            "repeating-linear-gradient(to bottom, transparent, transparent 38px, rgba(31,77,58,0.035) 39px)",
        }}
      />

      <div className="relative z-10 w-full max-w-[404px] rounded-[18px] border border-border bg-card p-9 pb-[34px] text-center shadow-[var(--ledger-shadow)]">
        <div className="mb-[30px] flex flex-col items-center">
          <div className="mb-3 flex size-[46px] items-center justify-center rounded-xl bg-primary shadow-[0_4px_14px_rgba(31,77,58,0.28)]">
            <Layers size={22} color="var(--primary-foreground)" />
          </div>
          <div className="font-heading text-[19px] font-semibold tracking-[-0.01em] text-foreground">
            {APP_NAME}
          </div>
          <div className="mt-[5px] font-mono text-[9.5px] tracking-[0.16em] text-[var(--ink-faint)]">
            DOCUMENT VAULT
          </div>
        </div>

        <div className="mx-auto mb-5 flex size-14 items-center justify-center rounded-full border border-[#E3CFA0] bg-[var(--gold-tint)]">
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
          className="w-full rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527]"
        >
          Restart login
        </button>
      </div>
    </div>
  )
}