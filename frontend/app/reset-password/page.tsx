"use client"

import { useState, type FormEvent } from "react"
import { useMutation } from "@tanstack/react-query"
import { CheckCircle2, Layers } from "lucide-react"
import { toast } from "sonner"

import { APP_NAME, ROUTES } from "@/lib/app-config"
import { requestPasswordReset } from "@/lib/auth/request-password-reset"

/**
 * Public page (see PUBLIC_PATHS in proxy.ts) — the person here is by
 * definition not signed in yet. Linked from the "Forgot password?" link on
 * app/login/page.tsx.
 *
 * There's no separate "reset password" form with new-password fields here
 * on purpose: this only submits the username/email to dms-core's
 * `POST /api/v1/auth/forgot-password` (see `PasswordResetService.requestReset`).
 * That issues a single-use token and emails a link to our own
 * `/update-password?token=...` page, entirely on dms-core's domain -
 * deliberately not Keycloak's hosted `executeActionsEmail(["UPDATE_PASSWORD"])`
 * required-action page, which this flow exists to avoid. New-user invites
 * (`components/users/user-invite-form.tsx` → `POST /api/v1/users` →
 * `PasswordResetService.issueForNewUser`) land on that same
 * `/update-password` page via the same token mechanism, distinguished
 * only by `PasswordResetToken.kind` once the link is opened.
 */
export default function ResetPasswordPage() {
  const [usernameOrEmail, setUsernameOrEmail] = useState("")

  const mutation = useMutation({
    mutationFn: () => requestPasswordReset(usernameOrEmail.trim()),
    onSuccess: () => {
      toast.success("If that account exists, a reset link is on its way.")
    },
    onError: (error) => {
      toast.error(
        error instanceof Error && error.message
          ? error.message
          : "Could not send the reset link. Please check your connection and try again."
      )
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
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

      <div className="relative z-10 w-full max-w-[404px] rounded-lg border border-border bg-card p-9 pb-8 shadow-[var(--ledger-shadow)]">
        <div className="mb-7 flex flex-col items-center">
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

        {mutation.isSuccess ? (
          <>
            <div className="mb-2 flex justify-center">
              <CheckCircle2 size={28} className="text-primary" />
            </div>
            <h1 className="mb-2 text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              Check your email
            </h1>
            <p className="mb-2 text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              If an account matches <span className="font-medium text-foreground">{usernameOrEmail}</span>,
              we&apos;ve sent a link to reset the password.
            </p>
            <p className="mb-2 text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              The link expires after a while — request a new one if it&apos;s gone stale.
            </p>
          </>
        ) : (
          <>
            <h1 className="mb-2 text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              Forgot your password?
            </h1>
            <p className="mb-[26px] text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              Enter your username or email to reset it
            </p>

            <form onSubmit={handleSubmit}>
              <div className="mb-[22px]">
                <label
                  htmlFor="usernameOrEmail"
                  className="mb-[7px] block text-[13px] font-semibold text-foreground"
                >
                  Username or email
                </label>
                <input
                  id="usernameOrEmail"
                  type="text"
                  placeholder="username"
                  autoComplete="username"
                  value={usernameOrEmail}
                  onChange={(e) => setUsernameOrEmail(e.target.value)}
                  className="w-full rounded-btn border border-[var(--border-strong)] bg-[var(--secondary)] px-[13px] py-[11px] text-sm text-foreground outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"
                />
              </div>

              <button
                type="submit"
                disabled={mutation.isPending || !usernameOrEmail.trim()}
                className="w-full rounded-btn border border-[var(--primary-hover)] bg-primary h-[38px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[var(--primary-hover)] disabled:cursor-not-allowed disabled:opacity-70"
              >
                {mutation.isPending ? "Sending…" : "Submit"}
              </button>
            </form>
          </>
        )}

        <a
          href={ROUTES.LOGIN}
          className="mt-[22px] flex items-center justify-center gap-[5px] text-[13px] font-semibold text-primary hover:underline"
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
