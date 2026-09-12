"use client"

import { useEffect, useState, type FormEvent } from "react"
import { useSearchParams } from "next/navigation"
import { useMutation } from "@tanstack/react-query"
import { CheckCircle2, Eye, EyeOff, Layers, XCircle } from "lucide-react"
import { toast } from "sonner"

import { API_BASE_URL } from "@/lib/api-config"

type TokenStatus = "checking" | "valid" | "invalid"

/** Mirrors `PasswordResetToken.TokenKind` on the backend. */
type TokenKind = "NEW_USER" | "PASSWORD_RESET"

interface ValidateTokenResult {
  valid: boolean
  kind: TokenKind | null
}

async function validateToken(token: string): Promise<ValidateTokenResult> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/auth/reset-password/validate?token=${encodeURIComponent(token)}`
  )
  if (!response.ok) return { valid: false, kind: null }
  const body = await response.json().catch(() => ({ valid: false, kind: null }))
  return { valid: Boolean(body.valid), kind: body.kind ?? null }
}

async function submitNewPassword(token: string, newPassword: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/reset-password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, newPassword }),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.message ?? "Something went wrong. Please try again.")
  }
}

/**
 * Public page (see PUBLIC_PATHS in proxy.ts) — lands here from the link in
 * either a password-reset email (`PasswordResetService.requestReset` /
 * `issueForAdminReset` — see the /reset-password page for step one of the
 * self-service flow) or a new-user invite email (`issueForNewUser`).
 * Entirely on our own domain and styled to match the rest of Ledger:
 * unlike the account-creation flow, this never redirects to Keycloak's
 * own hosted UPDATE_PASSWORD required-action page — dms-core verifies the
 * `token` query param itself (`GET /api/v1/auth/reset-password/validate`,
 * which also returns which of the two kinds below this token is) and, on
 * submit, redeems it (`POST /api/v1/auth/reset-password`), which sets the
 * real password in Keycloak via the Admin API without ever routing the
 * browser through it.
 * <p>
 * `tokenKind` only changes the heading/subtext/button copy on the
 * "valid, show the form" branch — the checking/invalid/success branches
 * render identically either way, since a plain "invalid or expired" or
 * "password updated" message reads fine regardless of which flow sent the
 * person here.
 */
export function UpdatePasswordPageContent() {
  const searchParams = useSearchParams()
  const token = searchParams.get("token") ?? ""

  const [tokenStatus, setTokenStatus] = useState<TokenStatus>(token ? "checking" : "invalid")
  const [tokenKind, setTokenKind] = useState<TokenKind | null>(null)
  const [newPassword, setNewPassword] = useState("")
  const [confirmPassword, setConfirmPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [validationError, setValidationError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) {
      setTokenStatus("invalid")
      return
    }
    let cancelled = false
    validateToken(token)
      .then((result) => {
        if (cancelled) return
        setTokenStatus(result.valid ? "valid" : "invalid")
        setTokenKind(result.kind)
      })
      .catch(() => {
        if (!cancelled) setTokenStatus("invalid")
      })
    return () => {
      cancelled = true
    }
  }, [token])

  const isNewUser = tokenKind === "NEW_USER"

  const mutation = useMutation({
    mutationFn: () => submitNewPassword(token, newPassword),
    onSuccess: () => {
      toast.success("Password updated.")
    },
    onError: (error) => {
      toast.error(error instanceof Error ? error.message : "Something went wrong. Please try again.")
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setValidationError(null)

    if (newPassword.length < 8) {
      setValidationError("Password must be at least 8 characters.")
      return
    }
    if (newPassword !== confirmPassword) {
      setValidationError("Passwords don't match.")
      return
    }
    mutation.mutate()
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

      <div className="relative z-10 w-full max-w-[404px] rounded-[18px] border border-border bg-card p-9 pb-8 shadow-[var(--ledger-shadow)]">
        <div className="mb-7 flex flex-col items-center">
          <div className="mb-3 flex size-[46px] items-center justify-center rounded-xl bg-primary shadow-[0_4px_14px_rgba(31,77,58,0.28)]">
            <Layers size={22} color="var(--primary-foreground)" />
          </div>
          <div className="font-heading text-[19px] font-semibold tracking-[-0.01em] text-foreground">
            Ledger
          </div>
          <div className="mt-[5px] font-mono text-[9.5px] tracking-[0.16em] text-[var(--ink-faint)]">
            DOCUMENT VAULT
          </div>
        </div>

        {tokenStatus === "checking" && (
          <p className="text-center text-[13.5px] leading-[1.5] text-muted-foreground">Checking your link…</p>
        )}

        {tokenStatus === "invalid" && (
          <>
            <div className="mb-2 flex justify-center">
              <XCircle size={28} className="text-[var(--rust)]" />
            </div>
            <h1 className="mb-2 text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              Link invalid or expired
            </h1>
            <p className="mb-2 text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              This password reset link is no longer valid. Request a new one to continue.
            </p>
            <a
              href="/reset-password"
              className="mt-4 flex w-full items-center justify-center rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527]"
            >
              Request a new link
            </a>
          </>
        )}

        {tokenStatus === "valid" && mutation.isSuccess && (
          <>
            <div className="mb-2 flex justify-center">
              <CheckCircle2 size={28} className="text-primary" />
            </div>
            <h1 className="mb-2 text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              Password updated
            </h1>
            <p className="mb-2 text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              Your password has been changed. You can now sign in with your new password.
            </p>
            <a
              href="/login"
              className="mt-4 flex w-full items-center justify-center rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527]"
            >
              Go to login
            </a>
          </>
        )}

        {tokenStatus === "valid" && !mutation.isSuccess && (
          <>
            <h1 className="mb-2 text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              {isNewUser ? "Welcome to Ledger — set your password to get started" : "Choose a new password"}
            </h1>
            <p className="mb-[26px] text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              {isNewUser
                ? "Choose a password to activate your account. Must be at least 8 characters."
                : "Must be at least 8 characters."}
            </p>

            <form onSubmit={handleSubmit}>
              <div className="mb-[17px]">
                <label
                  htmlFor="newPassword"
                  className="mb-[7px] block text-[13px] font-semibold text-foreground"
                >
                  New password
                </label>
                <div className="relative">
                  <input
                    id="newPassword"
                    type={showPassword ? "text" : "password"}
                    placeholder="••••••••••"
                    autoComplete="new-password"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    className="w-full rounded-[9px] border border-[var(--border-strong)] bg-[var(--secondary)] px-[13px] py-[11px] pr-10 text-sm text-foreground outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((prev) => !prev)}
                    tabIndex={-1}
                    aria-label={showPassword ? "Hide password" : "Show password"}
                    aria-pressed={showPassword}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)] transition-colors hover:text-foreground"
                  >
                    {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </div>

              <div className="mb-[22px]">
                <label
                  htmlFor="confirmPassword"
                  className="mb-[7px] block text-[13px] font-semibold text-foreground"
                >
                  Confirm new password
                </label>
                <input
                  id="confirmPassword"
                  type={showPassword ? "text" : "password"}
                  placeholder="••••••••••"
                  autoComplete="new-password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className="w-full rounded-[9px] border border-[var(--border-strong)] bg-[var(--secondary)] px-[13px] py-[11px] text-sm text-foreground outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"
                />
              </div>

              {(validationError || mutation.isError) && (
                <p className="mb-[17px] text-[12.5px] text-[var(--rust)]">
                  {validationError ?? (mutation.error as Error)?.message}
                </p>
              )}

              <button
                type="submit"
                disabled={mutation.isPending || !newPassword || !confirmPassword}
                className="w-full rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527] disabled:cursor-not-allowed disabled:opacity-70"
              >
                {isNewUser
                  ? mutation.isPending
                    ? "Setting up…"
                    : "Set password"
                  : mutation.isPending
                    ? "Updating…"
                    : "Update password"}
              </button>
            </form>
          </>
        )}

        <a
          href="/login"
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
