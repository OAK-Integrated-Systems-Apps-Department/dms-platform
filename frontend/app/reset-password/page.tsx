"use client"

import { useState, type FormEvent } from "react"
import { useMutation } from "@tanstack/react-query"
import { CheckCircle2, User } from "lucide-react"
import { toast } from "sonner"

import {
  AuthHeading,
  AuthShell,
  authButtonClass,
  authFieldClass,
  authInputClass,
  authInputIconClass,
} from "@/components/auth/auth-shell"
import { ROUTES } from "@/lib/app-config"
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
    <AuthShell>
      {mutation.isSuccess ? (
        <>
          <div className="mb-3 flex justify-center lg:justify-start">
            <CheckCircle2 size={32} className="text-primary" />
          </div>
          <AuthHeading
            title="Check your email"
            subtitle={
              <>
                If an account matches{" "}
                <span className="font-medium text-foreground">
                  {usernameOrEmail}
                </span>
                , we&apos;ve sent a link to reset the password. The link expires
                after a while — request a new one if it&apos;s gone stale.
              </>
            }
          />
        </>
      ) : (
        <>
          <AuthHeading
            title="Forgot your password?"
            subtitle="Enter your username or email to reset it."
          />

          <form onSubmit={handleSubmit} className="space-y-3 sm:space-y-5">
            <div>
              <label
                htmlFor="usernameOrEmail"
                className="mb-1 block text-[12.5px] font-semibold text-foreground max-lg:sr-only sm:mb-2 sm:text-[13px]"
              >
                Username or email
              </label>
              <div className={authFieldClass}>
                <User size={16} className={authInputIconClass} />
                <input
                  id="usernameOrEmail"
                  type="text"
                  placeholder="Username or email"
                  autoComplete="username"
                  value={usernameOrEmail}
                  onChange={(e) => setUsernameOrEmail(e.target.value)}
                  className={authInputClass}
                />
              </div>
            </div>

            <button
              type="submit"
              disabled={mutation.isPending || !usernameOrEmail.trim()}
              className={authButtonClass}
            >
              {mutation.isPending ? "Sending…" : "Submit"}
            </button>
          </form>
        </>
      )}

      <a
        href={ROUTES.LOGIN}
        className="mt-6 flex items-center justify-center gap-[5px] text-[13px] font-semibold text-primary hover:underline lg:justify-start"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2.5}
          className="size-[13px]"
        >
          <polyline points="15 18 9 12 15 6" />
        </svg>
        Back to login
      </a>
    </AuthShell>
  )
}
