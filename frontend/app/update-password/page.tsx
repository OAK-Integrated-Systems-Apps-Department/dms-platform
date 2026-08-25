"use client"

import { useMemo, useState, type FormEvent } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { useMutation } from "@tanstack/react-query"
import { AlertTriangle, CheckCircle2, Eye, EyeOff, Layers } from "lucide-react"

// This page's own fetch is dynamic (submit-triggered), but reading the
// token via useSearchParams still needs this to opt out of static
// rendering — same reasoning as the dev bypass in app/login/page.tsx.
export const dynamic = "force-dynamic"

interface UpdatePasswordResponse {
  ok: boolean
  error?: string
}

async function updatePassword(token: string, newPassword: string): Promise<UpdatePasswordResponse> {
  const response = await fetch("/api/auth/update-password", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, newPassword }),
  })
  return response.json()
}

function scoreStrength(value: string): number {
  let score = 0
  if (value.length >= 8) score++
  if (/[A-Z]/.test(value) && /[a-z]/.test(value)) score++
  if (/[0-9]/.test(value)) score++
  if (/[^A-Za-z0-9]/.test(value)) score++
  return score
}

const STRENGTH_COLORS = ["var(--rust)", "var(--gold)", "var(--gold)", "var(--primary)"]

/**
 * Public (see PUBLIC_PATHS in middleware.ts) — reached only via the emailed
 * link's ?token=... query param, both for the admin-invite flow
 * (VERIFY_EMAIL + UPDATE_PASSWORD, see app/register/page.tsx) and the
 * forgot-password flow (UPDATE_PASSWORD, see app/reset-password/page.tsx).
 * No session cookie is involved — `token` is the only proof of identity
 * here, verified server-side by /api/auth/update-password.
 */
export default function UpdatePasswordPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const token = searchParams.get("token") ?? ""

  const [newPassword, setNewPassword] = useState("")
  const [confirmPassword, setConfirmPassword] = useState("")
  const [showNew, setShowNew] = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)

  const strength = newPassword ? Math.max(1, scoreStrength(newPassword)) : 0
  const passwordsMatch = confirmPassword.length > 0 && confirmPassword === newPassword
  const passwordsMismatch = confirmPassword.length > 0 && confirmPassword !== newPassword
  const canSubmit = token.length > 0 && newPassword.length >= 8 && passwordsMatch

  const mutation = useMutation({
    mutationFn: () => updatePassword(token, newPassword),
  })

  const missingToken = useMemo(() => token.length === 0, [token])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    mutation.mutate()
  }

  const errorMessage = mutation.data?.ok === false ? mutation.data.error : null

  return (
    <div className="relative flex min-h-screen items-center justify-center bg-background px-5 py-10">
      <div
        className="pointer-events-none fixed inset-0 z-0"
        style={{
          backgroundImage:
            "repeating-linear-gradient(to bottom, transparent, transparent 38px, rgba(31,77,58,0.035) 39px)",
        }}
      />

      <div className="relative z-10 w-full max-w-[404px] rounded-[18px] border border-border bg-card p-9 pb-[34px] shadow-[var(--ledger-shadow)]">
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

        {missingToken ? (
          <div className="flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[#7A3623]">
            <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
            This link is missing its token. Use the link from your email, or request a new one.
          </div>
        ) : mutation.data?.ok ? (
          <>
            <div className="mb-2 flex justify-center">
              <CheckCircle2 size={28} className="text-primary" />
            </div>
            <h1 className="mb-2 text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              Password updated
            </h1>
            <p className="mb-6 text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              You can sign in with your new password now.
            </p>
            <button
              type="button"
              onClick={() => router.push("/login")}
              className="w-full rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527]"
            >
              Go to sign in
            </button>
          </>
        ) : (
          <>
            <h1 className="mb-2 text-center font-heading text-[22px] font-semibold tracking-[-0.01em] text-foreground">
              Update password
            </h1>
            <p className="mb-[26px] text-center text-[13.5px] leading-[1.5] text-muted-foreground">
              You need to change your password to activate your account.
            </p>

            {errorMessage && (
              <div className="mb-5 flex items-start gap-2.5 rounded-[10px] border border-[var(--rust)]/30 bg-[var(--rust-tint)] px-[13px] py-[11px] text-[13px] leading-[1.4] text-[#7A3623]">
                <AlertTriangle size={16} className="mt-px shrink-0 text-[var(--rust)]" />
                {errorMessage}
              </div>
            )}

            <form onSubmit={handleSubmit}>
              <div className="mb-[18px]">
                <label htmlFor="newPassword" className="mb-[7px] block text-[13px] font-semibold text-foreground">
                  New password
                </label>
                <div className="relative">
                  <input
                    id="newPassword"
                    type={showNew ? "text" : "password"}
                    placeholder="••••••••••"
                    autoComplete="new-password"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    className="w-full rounded-[9px] border border-[var(--border-strong)] bg-[var(--secondary)] py-[11px] pl-[13px] pr-[42px] text-sm text-foreground outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"
                  />
                  <button
                    type="button"
                    onClick={() => setShowNew((v) => !v)}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)] hover:text-muted-foreground"
                  >
                    {showNew ? <EyeOff size={17} /> : <Eye size={17} />}
                  </button>
                </div>
                <div className="mt-2 flex gap-1">
                  {Array.from({ length: 4 }, (_, i) => (
                    <i
                      key={i}
                      className="h-[3px] flex-1 rounded-sm"
                      style={{ background: i < strength ? STRENGTH_COLORS[strength - 1] : "var(--border)" }}
                    />
                  ))}
                </div>
              </div>

              <div className="mb-[18px]">
                <label
                  htmlFor="confirmPassword"
                  className="mb-[7px] block text-[13px] font-semibold text-foreground"
                >
                  Confirm password
                </label>
                <div className="relative">
                  <input
                    id="confirmPassword"
                    type={showConfirm ? "text" : "password"}
                    placeholder="••••••••••"
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    className="w-full rounded-[9px] border border-[var(--border-strong)] bg-[var(--secondary)] py-[11px] pl-[13px] pr-[42px] text-sm text-foreground outline-none transition-colors focus:border-primary focus:bg-card focus:ring-[3px] focus:ring-primary/15"
                  />
                  <button
                    type="button"
                    onClick={() => setShowConfirm((v) => !v)}
                    className="absolute top-1/2 right-3 -translate-y-1/2 text-[var(--ink-faint)] hover:text-muted-foreground"
                  >
                    {showConfirm ? <EyeOff size={17} /> : <Eye size={17} />}
                  </button>
                </div>
                <div className="mt-[7px] min-h-[14px] text-[11.5px]">
                  {passwordsMatch && <span className="text-primary">Passwords match</span>}
                  {passwordsMismatch && <span className="text-[var(--rust)]">Passwords do not match</span>}
                </div>
              </div>

              <button
                type="submit"
                disabled={!canSubmit || mutation.isPending}
                className="mt-1.5 w-full rounded-[10px] border border-[#123527] bg-primary py-[12.5px] text-[13px] font-bold tracking-[0.06em] text-primary-foreground uppercase transition-colors hover:bg-[#123527] disabled:cursor-not-allowed disabled:opacity-70"
              >
                {mutation.isPending ? "Updating…" : "Submit"}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  )
}
