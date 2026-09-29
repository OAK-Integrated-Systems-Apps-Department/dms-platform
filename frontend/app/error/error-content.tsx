// app/error/error-content.tsx
"use client"

import { useSearchParams } from "next/navigation"
import { AlertTriangle, Layers } from "lucide-react"

import { APP_NAME, ROUTES } from "@/lib/app-config"

const DEFAULT_MESSAGE =
  "Something went wrong on our end. Please try again, and if it keeps happening, contact your workspace administrator."

// Reads ?message= from the URL — wrapped in a <Suspense> boundary by the
// parent page.tsx so this can bail out of static rendering safely.
export function ErrorContent() {
  const searchParams = useSearchParams()
  const message = searchParams.get("message") || DEFAULT_MESSAGE

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

        <div className="mx-auto mb-5 flex size-14 items-center justify-center rounded-full border border-[color-mix(in_srgb,var(--rust)_40%,transparent)] bg-[var(--rust-tint)]">
          <AlertTriangle size={26} className="text-[var(--rust)]" />
        </div>

        <h1 className="mb-3 font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
          We are sorry…
        </h1>
        <p className="mb-[26px] text-[13.5px] leading-[1.65] text-muted-foreground">{message}</p>

        <a
          href={ROUTES.LOGIN}
          className="inline-flex w-full items-center justify-center gap-[5px] border-t border-dashed border-border pt-1.5 text-[13px] font-semibold text-primary hover:underline"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} className="size-[13px]">
            <polyline points="15 18 9 12 15 6" />
          </svg>
          Back to login
        </a>
      </div>
    </div>
  )
}